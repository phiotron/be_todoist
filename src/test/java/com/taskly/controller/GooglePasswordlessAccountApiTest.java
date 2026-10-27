package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.GoogleIntegrationTestBase;
import com.taskly.security.OpaqueTokens;
import com.taskly.support.GoogleTestTokens;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Spec bản 3 (5.9, 10.2, 12.1): tài khoản chỉ có Google với thao tác tài khoản của M3 — tắt 2FA không cần mật khẩu,
 * DELETE me → 409 password-required, xoá tài khoản xoá luôn liên kết Google (user_identities).
 */
class GooglePasswordlessAccountApiTest extends GoogleIntegrationTestBase {

    private ResultActions deleteMe(String accessToken, String password) throws Exception {
        return mockMvc.perform(delete("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"password": "%s"}
                        """.formatted(password)));
    }

    /** Đặt mật khẩu cho tài khoản chỉ có Google qua quên mật khẩu (như Task 17), trả access token đăng nhập mật khẩu. */
    private String setPasswordViaResetAndLogin(GoogleUser user) throws Exception {
        String rawToken = OpaqueTokens.generate();
        jdbcTemplate.update(
                "insert into password_reset_tokens (id, user_id, token_hash, expires_at, created_at, updated_at) "
                        + "values (?, ?, ?, ?, now(), now())",
                UUID.randomUUID(), user.id(), OpaqueTokens.sha256Hex(rawToken),
                OffsetDateTime.ofInstant(clock.instant().plus(Duration.ofMinutes(30)), ZoneOffset.UTC));
        mockMvc.perform(post("/api/v1/auth/password-reset/confirm").with(randomRemoteAddress())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token": "%s", "newPassword": "%s"}
                                """.formatted(rawToken, FIXTURE_PASSWORD)))
                .andExpect(status().is2xxSuccessful());
        return loginWithPassword(user.email());
    }

    @Test
    void googleOnlyAccount_deleteMeReturns409PasswordRequiredAndKeepsEverything() throws Exception {
        GoogleUser user = signInWithGoogle(googleTokens.idToken());

        deleteMe(user.accessToken(), FIXTURE_PASSWORD)
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("password-required"));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from users where id = ?", Integer.class, user.id())).isEqualTo(1);
        assertThat(countIdentities(user.subject())).isEqualTo(1);
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isOk());
    }

    // Spec 5.9 / 12.10 (chốt ở "Chỗ spec mâu thuẫn"): kiểm hasPassword TRƯỚC khi đòi password → 409 password-required
    // kể cả khi body không có mật khẩu ({}); không phải 400 của Bean Validation.
    @Test
    void googleOnlyAccount_deleteMeWithEmptyJsonObject_returns409PasswordRequired() throws Exception {
        GoogleUser user = signInWithGoogle(googleTokens.idToken());

        mockMvc.perform(delete("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("password-required"));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from users where id = ?", Integer.class, user.id())).isEqualTo(1);
    }

    @Test
    void googleOnlyAccount_afterSettingPassword_deleteMeRemovesUserAndIdentity_thenGoogleCreatesFreshAccount()
            throws Exception {
        GoogleTestTokens.IdToken first = googleTokens.idToken();
        GoogleUser user = signInWithGoogle(first);
        String accessToken = setPasswordViaResetAndLogin(user);

        deleteMe(accessToken, FIXTURE_PASSWORD).andExpect(status().isNoContent());

        assertThat(countUsersByEmail(user.email())).isZero();
        assertThat(countIdentities(first.getSubject())).isZero();
        // Cùng tài khoản Google (cùng sub, cùng email) đăng nhập lại → tài khoản MỚI, không mật khẩu (tình huống 1).
        postGoogleSignIn(googleTokens.idToken().subject(first.getSubject()).email(user.email()).sign())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id", not(user.id().toString())))
                .andExpect(jsonPath("$.user.hasPassword").value(false));
    }

    @Test
    void passwordAccountLinkedToGoogle_deleteMeRemovesIdentity() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        GoogleTestTokens.IdToken token = googleTokens.idToken();
        postLinkGoogle(user.accessToken(), idTokenJson(token.sign())).andExpect(status().isNoContent());

        deleteMe(user.accessToken(), FIXTURE_PASSWORD).andExpect(status().isNoContent());

        assertThat(countIdentities(token.getSubject())).isZero();
        postGoogleSignIn(googleTokens.idToken().subject(token.getSubject()).sign())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id", not(user.id().toString())));
    }

    @Test
    void googleOnlyAccountWithTwoFactor_disableNeedsOnlyCode() throws Exception {
        GoogleTestTokens.IdToken first = googleTokens.idToken();
        GoogleUser user = signInWithGoogle(first);
        TwoFactorSetup twoFactor = enableTwoFactor(user.accessToken());

        mockMvc.perform(post("/api/v1/me/2fa/disable")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "%s"}
                                """.formatted(currentTotp(twoFactor))))
                .andExpect(status().isNoContent());

        postGoogleSignIn(googleTokens.idToken().subject(first.getSubject()).sign())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(user.id().toString()));
    }

    // Không lộ việc tài khoản tồn tại / bật 2FA: tài khoản chưa có mật khẩu bị loại TRƯỚC nhánh 2FA của login.
    @Test
    void googleOnlyAccountWithTwoFactor_passwordLoginLooksLikeWrongPassword() throws Exception {
        GoogleUser user = signInWithGoogle(googleTokens.idToken());
        enableTwoFactor(user.accessToken());

        mockMvc.perform(post("/api/v1/auth/login").with(randomRemoteAddress())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(user.email(), FIXTURE_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.twoFactorToken").doesNotExist());
    }
}
