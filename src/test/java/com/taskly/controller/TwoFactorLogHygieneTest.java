package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.IntegrationTestBase;
import com.taskly.support.TwoFactorSteps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * Đi hết các luồng 2FA (bật, đăng nhập hai bước sai + đúng bằng mã khôi phục, tạo lại mã, tắt) rồi kiểm toàn bộ log của
 * ứng dụng không chứa secret, mã TOTP, mã khôi phục, twoFactorToken (spec 5.9, CLAUDE.md luật 3). Chỉ là rào chắn cho
 * hồi quy: nếu ai đó thêm log chứa giá trị nhạy cảm, test đỏ. Nếu package của CapturedOutput khác ở Spring Boot 4.1.1
 * thì sửa import (lớp vẫn nằm trong spring-boot-test).
 */
@ExtendWith(OutputCaptureExtension.class)
class TwoFactorLogHygieneTest extends IntegrationTestBase {

    @Test
    void twoFactorFlows_neverWriteSecretsCodesOrTokensToTheLog(CapturedOutput output) throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.Enabled enabled = TwoFactorSteps.enable(mockMvc, clock, user.accessToken());
        String firstTotp = TwoFactorSteps.code(enabled.secret(), clock);
        String recovery = enabled.recoveryCodes().get(0);
        String twoFactorToken = JsonPath.read(body(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(user.email(), FIXTURE_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andReturn()), "$.twoFactorToken");
        mockMvc.perform(post("/api/v1/auth/2fa/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"twoFactorToken\": \"%s\", \"code\": \"000000\"}".formatted(twoFactorToken)))
                .andExpect(status().isUnauthorized());
        String session = body(mockMvc.perform(post("/api/v1/auth/2fa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"twoFactorToken\": \"%s\", \"code\": \"%s\"}".formatted(twoFactorToken, recovery)))
                .andExpect(status().isOk())
                .andReturn());
        String accessToken = JsonPath.read(session, "$.accessToken");
        String lastTotp = TwoFactorSteps.nextCode(enabled.secret(), clock);
        mockMvc.perform(post("/api/v1/me/2fa/recovery-codes")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"%s\"}".formatted(lastTotp)))
                .andExpect(status().isOk());
        String disableTotp = TwoFactorSteps.nextCode(enabled.secret(), clock);
        mockMvc.perform(post("/api/v1/me/2fa/disable")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\": \"%s\", \"code\": \"%s\"}".formatted(FIXTURE_PASSWORD, disableTotp)))
                .andExpect(status().isNoContent());

        assertThat(output.getAll())
                .doesNotContain(enabled.secret(), twoFactorToken, recovery, firstTotp, lastTotp, disableTotp,
                        FIXTURE_PASSWORD, accessToken, user.refreshToken());
    }

    /** Mất / đổi khoá mã hoá là lỗi cấu hình, không phải lỗi runtime mỗi lần người dùng gõ mã: WARN, không stack trace, không dữ liệu. */
    @Test
    void undecryptableSecret_isLoggedAsWarning_withoutCiphertextOrCode(CapturedOutput output) throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.Enabled enabled = TwoFactorSteps.enable(mockMvc, clock, user.accessToken());
        jdbcTemplate.update("update user_totp set secret_encrypted = 'v1:ban-ma-hong-khong-giai-duoc' where user_id = ?",
                user.id());
        String token = JsonPath.read(body(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(user.email(), FIXTURE_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andReturn()), "$.twoFactorToken");
        String code = TwoFactorSteps.nextCode(enabled.secret(), clock);
        mockMvc.perform(post("/api/v1/auth/2fa/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"twoFactorToken\": \"%s\", \"code\": \"%s\"}".formatted(token, code)))
                .andExpect(status().isUnauthorized());

        String line = output.getAll().lines().filter(l -> l.contains("TOTP_ENCRYPTION_KEY")).findFirst()
                .orElseThrow();
        assertThat(line).contains(" WARN ").doesNotContain(" ERROR ")
                .doesNotContain("ban-ma-hong-khong-giai-duoc").doesNotContain(code).doesNotContain(enabled.secret());
        assertThat(output.getAll()).as("không in stack trace của lỗi giải mã")
                .doesNotContain("IllegalStateException: ");
    }
}
