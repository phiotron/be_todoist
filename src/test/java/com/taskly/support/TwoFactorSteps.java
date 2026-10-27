package com.taskly.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.security.TotpCodec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Bật 2FA qua API và sinh mã TOTP trong test (spec 14.4: secret do API trả + Clock của test). Mã của một bước chỉ dùng
 * được một lần → cần mã mới thì gọi nextCode (dời MutableClock 30 giây). KHÔNG in secret / mã ra log test.
 */
public final class TwoFactorSteps {

    /** Kết quả bật 2FA: secret base32 và 10 mã khôi phục. */
    public record Enabled(String secret, List<String> recoveryCodes) {
    }

    private TwoFactorSteps() {
    }

    public static String setup(MockMvc mockMvc, String accessToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/me/2fa/setup")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.secret");
    }

    /** Mã 6 số của bước hiện tại theo đồng hồ test. */
    public static String code(String secret, Clock clock) {
        return TotpCodec.code(TotpCodec.fromBase32(secret), TotpCodec.step(clock.instant()));
    }

    /**
     * Mã 6 số SAI chắc chắn: khác mã của mọi bước trong [hiện tại − 2, hiện tại + 2] (cửa sổ server nhận là ±1) — không dùng
     * "000000" cố định vì có xác suất nhỏ trùng một mã hợp lệ.
     */
    public static String wrongCode(String secret, Clock clock) {
        byte[] raw = TotpCodec.fromBase32(secret);
        long current = TotpCodec.step(clock.instant());
        for (int candidate = 0; candidate < 1_000_000; candidate += 111_111) {
            String wrong = String.format(java.util.Locale.ROOT, "%06d", candidate);
            boolean collides = false;
            for (long step = current - 2; step <= current + 2; step++) {
                collides |= wrong.equals(TotpCodec.code(raw, step));
            }
            if (!collides) {
                return wrong;
            }
        }
        throw new IllegalStateException("Không chọn được mã sai");
    }

    /** Sang bước 30 giây kế tiếp rồi trả mã của bước đó (mã trước đã dùng thì không dùng lại được). */
    public static String nextCode(String secret, MutableClock clock) {
        clock.advance(Duration.ofSeconds(TotpCodec.PERIOD_SECONDS));
        return code(secret, clock);
    }

    /** setup + enable bằng mã hiện tại; trả secret và mã khôi phục. */
    public static Enabled enable(MockMvc mockMvc, MutableClock clock, String accessToken) throws Exception {
        String secret = setup(mockMvc, accessToken);
        MvcResult result = mockMvc.perform(post("/api/v1/me/2fa/enable")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"" + code(secret, clock) + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        List<String> recoveryCodes = JsonPath.read(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.recoveryCodes");
        return new Enabled(secret, recoveryCodes);
    }
}
