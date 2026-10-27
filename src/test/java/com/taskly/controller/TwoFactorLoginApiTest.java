package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.IntegrationTestBase;
import com.taskly.support.PasswordResetMails;
import com.taskly.support.TwoFactorSteps;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Đăng nhập hai bước (spec 12.1): login → 401 two-factor-required + twoFactorToken → auth/2fa/verify → cặp token. */
class TwoFactorLoginApiTest extends IntegrationTestBase {

    private static final String NEW_FIXTURE_PASSWORD = "Fixture-New-Pass-456";

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s"}
                        """.formatted(email, password)));
    }

    private ResultActions verify(String twoFactorToken, String code) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/2fa/verify").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"twoFactorToken": "%s", "code": "%s"}
                        """.formatted(twoFactorToken, code)));
    }

    /** Đăng nhập tài khoản đã bật 2FA → lấy twoFactorToken từ ProblemDetail 401. */
    private String twoFactorToken(String email) throws Exception {
        return JsonPath.read(body(login(email, FIXTURE_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andReturn()), "$.twoFactorToken");
    }

    private int refreshTokens(UUID userId) {
        return jdbcTemplate.queryForObject("select count(*) from refresh_tokens where user_id = ?", Integer.class,
                userId);
    }

    @Test
    void login_withoutTwoFactor_returnsTokensAsBefore() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        login(user.email(), FIXTURE_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.twoFactorEnabled").value(false));
    }

    @Test
    void login_withTwoFactor_returns401WithCodeAndToken_andIssuesNoSession() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.enable(mockMvc, clock, user.accessToken());
        int before = refreshTokens(user.id());

        login(user.email(), FIXTURE_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("two-factor-required"))
                .andExpect(jsonPath("$.twoFactorToken").isNotEmpty())
                .andExpect(jsonPath("$.detail").value("Tài khoản đã bật xác minh hai bước, hãy nhập mã xác minh"))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist());

        assertThat(refreshTokens(user.id())).isEqualTo(before);
    }

    @Test
    void login_withTwoFactor_wrongPassword_isPlainInvalidCredentials() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.enable(mockMvc, clock, user.accessToken());

        login(user.email(), "Wrong-Pass-999")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Email hoặc mật khẩu không đúng"))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.twoFactorToken").doesNotExist());
    }

    @Test
    void verify_validTotp_returnsTokenPair_andTheAccessTokenWorks() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.Enabled enabled = TwoFactorSteps.enable(mockMvc, clock, user.accessToken());
        String token = twoFactorToken(user.email());

        String json = body(verify(token, TwoFactorSteps.nextCode(enabled.secret(), clock))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.accessTokenExpiresIn").value(900))
                .andExpect(jsonPath("$.user.id").value(user.id().toString()))
                .andExpect(jsonPath("$.user.twoFactorEnabled").value(true))
                .andReturn());

        String accessToken = JsonPath.read(json, "$.accessToken");
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.twoFactorEnabled").value(true));
    }

    @Test
    void verify_wrongCode_returns401() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.Enabled enabled = TwoFactorSteps.enable(mockMvc, clock, user.accessToken());
        String token = twoFactorToken(user.email());
        String code = TwoFactorSteps.nextCode(enabled.secret(), clock);

        verify(token, TwoFactorSteps.wrongCode(enabled.secret(), clock))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("two-factor-code-invalid"))
                .andExpect(jsonPath("$.detail").value("Mã xác minh không đúng"));
        verify(token, "zzzz-zzzz")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("two-factor-code-invalid"));
    }

    @Test
    void verify_wrongCode_doesNotBurnTheToken_soTheRightCodeThenSignsIn() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.Enabled enabled = TwoFactorSteps.enable(mockMvc, clock, user.accessToken());
        String token = twoFactorToken(user.email());
        String right = TwoFactorSteps.nextCode(enabled.secret(), clock);

        verify(token, TwoFactorSteps.wrongCode(enabled.secret(), clock))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("two-factor-code-invalid"));

        verify(token, right)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void verify_usedToken_isTokenInvalid_evenWhenTheCodeIsWrong() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.Enabled enabled = TwoFactorSteps.enable(mockMvc, clock, user.accessToken());
        String token = twoFactorToken(user.email());
        verify(token, TwoFactorSteps.nextCode(enabled.secret(), clock)).andExpect(status().isOk());

        verify(token, "000000")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("two-factor-token-invalid"))
                .andExpect(jsonPath("$.detail")
                        .value("Phiên xác minh hai bước không hợp lệ hoặc đã hết hạn, hãy đăng nhập lại"));
    }

    @Test
    void verify_overlongCode_returns400WithFieldError_andTheTokenStaysAlive() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.Enabled enabled = TwoFactorSteps.enable(mockMvc, clock, user.accessToken());
        String token = twoFactorToken(user.email());

        verify(token, "x".repeat(33))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("code")))
                .andExpect(jsonPath("$.code").doesNotExist());

        verify(token, TwoFactorSteps.nextCode(enabled.secret(), clock)).andExpect(status().isOk());
    }

    @Test
    void verify_bothFailures_haveTheirCodeAndAnEnglishDetail() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.Enabled enabled = TwoFactorSteps.enable(mockMvc, clock, user.accessToken());
        String token = twoFactorToken(user.email());
        String right = TwoFactorSteps.nextCode(enabled.secret(), clock);
        String wrong = TwoFactorSteps.wrongCode(enabled.secret(), clock);

        mockMvc.perform(post("/api/v1/auth/2fa/verify").contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .content("""
                                {"twoFactorToken": "%s", "code": "%s"}
                                """.formatted(token, wrong)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("two-factor-code-invalid"))
                .andExpect(jsonPath("$.detail").value("The verification code is incorrect"));
        mockMvc.perform(post("/api/v1/auth/2fa/verify").contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .content("""
                                {"twoFactorToken": "not-a-jwt", "code": "%s"}
                                """.formatted(right)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("two-factor-token-invalid"))
                .andExpect(jsonPath("$.detail")
                        .value("The two-step verification session is invalid or has expired, please sign in again"));
    }

    @Test
    void verify_sameTotpTwice_secondIsRejected() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.Enabled enabled = TwoFactorSteps.enable(mockMvc, clock, user.accessToken());
        String code = TwoFactorSteps.nextCode(enabled.secret(), clock);

        verify(twoFactorToken(user.email()), code).andExpect(status().isOk());
        verify(twoFactorToken(user.email()), code)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("two-factor-code-invalid"));
    }

    @Test
    void verify_recoveryCode_worksOnce_andIsConsumed() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.Enabled enabled = TwoFactorSteps.enable(mockMvc, clock, user.accessToken());
        String recovery = enabled.recoveryCodes().get(0);

        String json = body(verify(twoFactorToken(user.email()), recovery)
                .andExpect(status().isOk())
                .andReturn());
        mockMvc.perform(get("/api/v1/me/2fa")
                        .header(HttpHeaders.AUTHORIZATION, bearer(JsonPath.read(json, "$.accessToken"))))
                .andExpect(jsonPath("$.recoveryCodesLeft").value(9));

        verify(twoFactorToken(user.email()), recovery)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("two-factor-code-invalid"));
    }

    @Test
    void verify_whenTheSecretCannotBeDecrypted_totpIsRejected_butRecoveryCodeStillWorks() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.Enabled enabled = TwoFactorSteps.enable(mockMvc, clock, user.accessToken());
        // Giả lập mất khoá mã hoá (spec 17): bản mã trong DB không còn giải được.
        jdbcTemplate.update("update user_totp set secret_encrypted = 'ban-ma-hong' where user_id = ?", user.id());
        String token = twoFactorToken(user.email());

        verify(token, TwoFactorSteps.nextCode(enabled.secret(), clock))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("two-factor-code-invalid"));
        verify(token, enabled.recoveryCodes().get(0)).andExpect(status().isOk());
    }

    @Test
    void verify_expiredTwoFactorToken_returns401() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.Enabled enabled = TwoFactorSteps.enable(mockMvc, clock, user.accessToken());
        String token = twoFactorToken(user.email());
        clock.advance(Duration.ofMinutes(7)); // 5 phút + dung sai 60 giây đã qua

        verify(token, TwoFactorSteps.code(enabled.secret(), clock))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("two-factor-token-invalid"))
                .andExpect(jsonPath("$.detail")
                        .value("Phiên xác minh hai bước không hợp lệ hoặc đã hết hạn, hãy đăng nhập lại"));
    }

    @Test
    void verify_withAnAccessTokenInsteadOfTwoFactorToken_returns401() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.Enabled enabled = TwoFactorSteps.enable(mockMvc, clock, user.accessToken());

        verify(user.accessToken(), TwoFactorSteps.nextCode(enabled.secret(), clock))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("two-factor-token-invalid"))
                .andExpect(jsonPath("$.detail")
                        .value("Phiên xác minh hai bước không hợp lệ hoặc đã hết hạn, hãy đăng nhập lại"));
    }

    @Test
    void verify_sameTwoFactorTokenCannotBeUsedTwice() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.Enabled enabled = TwoFactorSteps.enable(mockMvc, clock, user.accessToken());
        String token = twoFactorToken(user.email());

        verify(token, TwoFactorSteps.nextCode(enabled.secret(), clock)).andExpect(status().isOk());
        clock.advance(Duration.ofSeconds(30)); // sang bước TOTP kế → mã mới hợp lệ, chỉ còn token đã dùng là cớ từ chối
        verify(token, TwoFactorSteps.nextCode(enabled.secret(), clock))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("two-factor-token-invalid"))
                .andExpect(jsonPath("$.detail")
                        .value("Phiên xác minh hai bước không hợp lệ hoặc đã hết hạn, hãy đăng nhập lại"));
    }

    /** Khoá chốt findForUpdate: hai twoFactorToken khác nhau + cùng một mã TOTP chạy song song → đúng một 200. */
    @Test
    void verify_sameTotpFromTwoParallelRequests_succeedsForExactlyOne() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.Enabled enabled = TwoFactorSteps.enable(mockMvc, clock, user.accessToken());
        String tokenA = twoFactorToken(user.email());
        String tokenB = twoFactorToken(user.email());
        String code = TwoFactorSteps.nextCode(enabled.secret(), clock);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
            for (String token : new String[] {tokenA, tokenB}) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return verify(token, code).andReturn().getResponse().getStatus();
                }));
            }
            ready.await();
            go.countDown();
            java.util.List<Integer> statuses = new java.util.ArrayList<>();
            for (java.util.concurrent.Future<Integer> result : results) {
                statuses.add(result.get(30, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 401);
        } finally {
            pool.shutdownNow();
        }
    }

    /** AAD = userId: bản mã chép từ user này sang user khác không giải được → TOTP bị từ chối (không lộ secret của ai). */
    @Test
    void verify_withASecretCiphertextCopiedFromAnotherUser_isRejected() throws Exception {
        RegisteredUser victim = registerUser(uniqueEmail());
        RegisteredUser attacker = registerUser(uniqueEmail());
        TwoFactorSteps.Enabled victimEnabled = TwoFactorSteps.enable(mockMvc, clock, victim.accessToken());
        TwoFactorSteps.enable(mockMvc, clock, attacker.accessToken());
        jdbcTemplate.update("update user_totp set secret_encrypted = "
                + "(select secret_encrypted from user_totp where user_id = ?) where user_id = ?",
                victim.id(), attacker.id());
        String token = twoFactorToken(attacker.email());

        verify(token, TwoFactorSteps.nextCode(victimEnabled.secret(), clock))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("two-factor-code-invalid"));
    }

    @Test
    void verify_overlongTwoFactorToken_returns400WithFieldError() throws Exception {
        verify("a".repeat(2049), "123456")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("twoFactorToken")))
                .andExpect(jsonPath("$.errors[?(@.field == 'twoFactorToken')].message",
                        org.hamcrest.Matchers.contains("Phiên xác minh hai bước quá dài")));
    }

    @Test
    void verify_whenTheUserWasDeletedBetweenTheTwoSteps_isTokenInvalidNotSessionInvalid() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.Enabled enabled = TwoFactorSteps.enable(mockMvc, clock, user.accessToken());
        String token = twoFactorToken(user.email());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\": \"%s\", \"code\": \"%s\"}".formatted(
                                FIXTURE_PASSWORD, enabled.recoveryCodes().get(0))))
                .andExpect(status().isNoContent());

        verify(token, enabled.recoveryCodes().get(1))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("two-factor-token-invalid"));
    }

    @Test
    void verify_whenTwoFactorWasTurnedOffBetweenTheTwoSteps_isTokenInvalid() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.Enabled enabled = TwoFactorSteps.enable(mockMvc, clock, user.accessToken());
        String token = twoFactorToken(user.email());
        mockMvc.perform(post("/api/v1/me/2fa/disable")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\": \"%s\", \"code\": \"%s\"}".formatted(
                                FIXTURE_PASSWORD, enabled.recoveryCodes().get(0))))
                .andExpect(status().isNoContent());

        verify(token, enabled.recoveryCodes().get(1))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("two-factor-token-invalid"));
    }

    @Test
    void twoFactorToken_cannotBeUsedAsBearerAccessToken() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.enable(mockMvc, clock, user.accessToken());

        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(twoFactorToken(user.email()))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void verify_isPublic_andIgnoresAStaleAuthorizationHeader() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.Enabled enabled = TwoFactorSteps.enable(mockMvc, clock, user.accessToken());
        String token = twoFactorToken(user.email());

        mockMvc.perform(post("/api/v1/auth/2fa/verify").contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, bearer("access-token-cu-da-het-han"))
                        .content("""
                                {"twoFactorToken": "%s", "code": "%s"}
                                """.formatted(token, TwoFactorSteps.nextCode(enabled.secret(), clock))))
                .andExpect(status().isOk());
    }

    @Test
    void verify_blankFields_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/auth/2fa/verify").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("twoFactorToken", "code")));
    }

    @Test
    void login_withTwoFactor_problemInEnglish() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.enable(mockMvc, clock, user.accessToken());

        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(user.email(), FIXTURE_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthenticated"))
                .andExpect(jsonPath("$.detail")
                        .value("Two-step verification is on for this account, please enter your verification code"))
                .andExpect(jsonPath("$.code").value("two-factor-required"));
    }

    @Test
    void passwordReset_doesNotTurnOffTwoFactor() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.enable(mockMvc, clock, user.accessToken());
        mockMvc.perform(post("/api/v1/auth/password-reset/request").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s"}
                                """.formatted(user.email())))
                .andExpect(status().isOk());
        String resetToken = PasswordResetMails.tokenFrom(mailbox.sentTo(user.email()).get(0));
        mockMvc.perform(post("/api/v1/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token": "%s", "newPassword": "%s"}
                                """.formatted(resetToken, NEW_FIXTURE_PASSWORD)))
                .andExpect(status().isNoContent());

        login(user.email(), NEW_FIXTURE_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("two-factor-required"));
    }
}
