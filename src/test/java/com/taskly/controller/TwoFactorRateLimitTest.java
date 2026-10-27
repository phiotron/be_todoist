package com.taskly.controller;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.IntegrationTestBase;
import com.taskly.common.error.ErrorMessage;
import com.taskly.support.Texts;
import com.taskly.support.TwoFactorSteps;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Giới hạn số lần thử THEO TÀI KHOẢN (spec mục 5.9: 5 lần / 5 phút, vượt → 429 + Retry-After): một bộ đếm mã 2FA dùng chung
 * cho auth/2fa/verify, me/2fa/enable | disable | recovery-codes và DELETE me; một bộ đếm mật khẩu riêng cho DELETE me và
 * me/2fa/disable (đoán mật khẩu qua endpoint có JWT).
 */
class TwoFactorRateLimitTest extends IntegrationTestBase {

    private static final String WRONG_PASSWORD = "Wrong-Pass-999";

    /** Bật 2FA (me/2fa/enable cũng tính một lần thử) rồi sang cửa sổ mới để mỗi test bắt đầu với đủ 5 lần. */
    private TwoFactorSteps.Enabled enable(RegisteredUser user) throws Exception {
        TwoFactorSteps.Enabled enabled = TwoFactorSteps.enable(mockMvc, clock, user.accessToken());
        clock.advance(Duration.ofSeconds(301));
        return enabled;
    }

    private String twoFactorToken(String email) throws Exception {
        return JsonPath.read(body(mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(email, FIXTURE_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andReturn()), "$.twoFactorToken");
    }

    private ResultActions verify(String token, String code) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/2fa/verify").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"twoFactorToken": "%s", "code": "%s"}
                        """.formatted(token, code)));
    }

    private ResultActions disable(RegisteredUser user, String password, String code) throws Exception {
        return authPost(user.accessToken(), "/api/v1/me/2fa/disable", """
                {"password": "%s", "code": "%s"}
                """.formatted(password, code));
    }

    private ResultActions recoveryCodes(RegisteredUser user, String code) throws Exception {
        return authPost(user.accessToken(), "/api/v1/me/2fa/recovery-codes", """
                {"code": "%s"}
                """.formatted(code));
    }

    private ResultActions deleteMe(RegisteredUser user, String json) throws Exception {
        return mockMvc.perform(delete("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions deleteMe(RegisteredUser user, String password, String code) throws Exception {
        return deleteMe(user, """
                {"password": "%s", "code": "%s"}
                """.formatted(password, code));
    }

    private void assertTooManyRequests(ResultActions actions) throws Exception {
        actions.andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, matchesPattern("[1-9][0-9]{0,2}")))
                .andExpect(jsonPath("$.detail").value(Texts.vi(ErrorMessage.TOO_MANY_REQUESTS)));
    }

    @Test
    void verify_fifthWrongAttemptIs401_sixthIs429WithRetryAfter_evenWithTheRightCode() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.Enabled enabled = enable(user);
        String token = twoFactorToken(user.email());
        String valid = TwoFactorSteps.nextCode(enabled.secret(), clock);
        String wrong = TwoFactorSteps.wrongCode(enabled.secret(), clock);

        for (int i = 0; i < 5; i++) {
            verify(token, wrong)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("two-factor-code-invalid")); // token còn sống
        }

        assertTooManyRequests(verify(token, valid));
    }

    @Test
    void verify_successfulAttemptsAreCountedToo() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.Enabled enabled = enable(user);

        for (int i = 0; i < 5; i++) {
            verify(twoFactorToken(user.email()), TwoFactorSteps.nextCode(enabled.secret(), clock))
                    .andExpect(status().isOk());
        }

        assertTooManyRequests(verify(twoFactorToken(user.email()), TwoFactorSteps.nextCode(enabled.secret(), clock)));
    }

    @Test
    void limitIsPerAccount() throws Exception {
        RegisteredUser alice = registerUser(uniqueEmail());
        RegisteredUser bob = registerUser(uniqueEmail());
        TwoFactorSteps.Enabled aliceKeys = enable(alice);
        TwoFactorSteps.Enabled bobKeys = enable(bob);
        String aliceToken = twoFactorToken(alice.email());
        String bobToken = twoFactorToken(bob.email());
        String aliceWrong = TwoFactorSteps.wrongCode(aliceKeys.secret(), clock);
        for (int i = 0; i < 5; i++) {
            verify(aliceToken, aliceWrong).andExpect(status().isUnauthorized());
        }

        assertTooManyRequests(verify(aliceToken, aliceWrong));
        verify(bobToken, TwoFactorSteps.nextCode(bobKeys.secret(), clock)).andExpect(status().isOk());
    }

    @Test
    void allowedAgainAfterTheWindow() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.Enabled enabled = enable(user);
        String token = twoFactorToken(user.email());
        String wrong = TwoFactorSteps.wrongCode(enabled.secret(), clock);
        for (int i = 0; i < 5; i++) {
            verify(token, wrong).andExpect(status().isUnauthorized());
        }
        verify(token, wrong).andExpect(status().isTooManyRequests());
        clock.advance(Duration.ofSeconds(301)); // limiter đọc giờ từ bean Clock = MutableClock

        verify(twoFactorToken(user.email()), TwoFactorSteps.code(enabled.secret(), clock))
                .andExpect(status().isOk());
    }

    @Test
    void tooManyRequests_isTranslatedToEnglish() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.Enabled enabled = enable(user);
        String token = twoFactorToken(user.email());
        String wrong = TwoFactorSteps.wrongCode(enabled.secret(), clock);
        for (int i = 0; i < 5; i++) {
            verify(token, wrong);
        }

        mockMvc.perform(post("/api/v1/auth/2fa/verify").contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .content("""
                                {"twoFactorToken": "%s", "code": "%s"}
                                """.formatted(token, wrong)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.title").value("Too many requests"))
                .andExpect(jsonPath("$.detail").value("Too many requests, please try again later"));
    }

    // ---- Dùng chung giữa mọi chỗ nhận mã 2FA

    @Test
    void codeAttemptsAreSharedAcrossVerifyDisableRecoveryCodesAndDeleteMe() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.Enabled enabled = enable(user);
        String wrong = TwoFactorSteps.wrongCode(enabled.secret(), clock);
        String token = twoFactorToken(user.email());

        verify(token, wrong).andExpect(status().isUnauthorized());
        verify(token, wrong).andExpect(status().isUnauthorized());
        disable(user, FIXTURE_PASSWORD, wrong).andExpect(status().isBadRequest());
        recoveryCodes(user, wrong).andExpect(status().isBadRequest());
        deleteMe(user, FIXTURE_PASSWORD, wrong).andExpect(status().isBadRequest());

        // Năm lần đã dùng hết ở bốn chỗ khác nhau: mọi chỗ đều 429, kể cả khi mã đúng.
        String valid = TwoFactorSteps.nextCode(enabled.secret(), clock);
        assertTooManyRequests(verify(token, valid));
        assertTooManyRequests(recoveryCodes(user, valid));
        assertTooManyRequests(disable(user, FIXTURE_PASSWORD, valid));
        assertTooManyRequests(deleteMe(user, FIXTURE_PASSWORD, valid));
    }

    @Test
    void enable_wrongCodesAreLimitedPerAccount() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String secret = TwoFactorSteps.setup(mockMvc, user.accessToken());
        String wrong = TwoFactorSteps.wrongCode(secret, clock);
        for (int i = 0; i < 5; i++) {
            authPost(user.accessToken(), "/api/v1/me/2fa/enable", "{\"code\": \"" + wrong + "\"}")
                    .andExpect(status().isBadRequest());
        }

        assertTooManyRequests(authPost(user.accessToken(), "/api/v1/me/2fa/enable",
                "{\"code\": \"" + TwoFactorSteps.code(secret, clock) + "\"}"));
    }

    @Test
    void deleteMe_wrongRecoveryCodesAreLimited_andTheAccountSurvives() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.Enabled enabled = enable(user);
        for (int i = 0; i < 5; i++) {
            deleteMe(user, FIXTURE_PASSWORD, "aaaa-bbbb").andExpect(status().isBadRequest());
        }

        assertTooManyRequests(deleteMe(user, FIXTURE_PASSWORD, enabled.recoveryCodes().get(0)));
        authGet(user.accessToken(), "/api/v1/me").andExpect(status().isOk());
    }

    // ---- Sai mật khẩu ở endpoint có JWT

    @Test
    void deleteMe_wrongPasswordIsLimited_evenWithTheRightPasswordAfterwards() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        for (int i = 0; i < 5; i++) {
            deleteMe(user, "{\"password\": \"" + WRONG_PASSWORD + "\"}").andExpect(status().isBadRequest());
        }

        assertTooManyRequests(deleteMe(user, "{\"password\": \"" + FIXTURE_PASSWORD + "\"}"));
        authGet(user.accessToken(), "/api/v1/me").andExpect(status().isOk());
    }

    @Test
    void disable_wrongPasswordIsLimited_andTwoFactorStaysOn() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.Enabled enabled = enable(user);
        for (int i = 0; i < 5; i++) {
            disable(user, WRONG_PASSWORD, TwoFactorSteps.wrongCode(enabled.secret(), clock))
                    .andExpect(status().isBadRequest());
        }

        assertTooManyRequests(disable(user, FIXTURE_PASSWORD, TwoFactorSteps.nextCode(enabled.secret(), clock)));
        authGet(user.accessToken(), "/api/v1/me/2fa").andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void passwordAndCodeCountersAreIndependent() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.Enabled enabled = enable(user);
        for (int i = 0; i < 5; i++) {
            disable(user, WRONG_PASSWORD, TwoFactorSteps.wrongCode(enabled.secret(), clock))
                    .andExpect(status().isBadRequest()); // sai mật khẩu → chưa tới bước kiểm mã
        }

        verify(twoFactorToken(user.email()), TwoFactorSteps.nextCode(enabled.secret(), clock))
                .andExpect(status().isOk());
    }
}
