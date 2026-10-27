package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.GoogleIntegrationTestBase;
import com.taskly.security.OpaqueTokens;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** Tài khoản chỉ có Google: đổi mật khẩu bị chặn rõ ràng; đặt mật khẩu qua quên mật khẩu rồi mới bỏ liên kết. */
class GooglePasswordInteropApiTest extends GoogleIntegrationTestBase {

    @Test
    void googleOnlyAccount_changePasswordExplainsToUseForgotPassword() throws Exception {
        GoogleUser user = signInWithGoogle(googleTokens.idToken());

        mockMvc.perform(post("/api/v1/me/password")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword": "%s", "newPassword": "%s"}
                                """.formatted(FIXTURE_PASSWORD, FIXTURE_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "Tài khoản chưa có mật khẩu. Hãy dùng chức năng Quên mật khẩu để đặt mật khẩu"));
    }

    @Test
    void googleOnlyAccount_setsPasswordViaResetThenCanLoginAndUnlink() throws Exception {
        GoogleUser user = signInWithGoogle(googleTokens.idToken());
        // Chèn thẳng token đặt lại (chỉ lưu SHA-256 hex, như refresh token) — test không cần đọc thư.
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

        String json = body(mockMvc.perform(post("/api/v1/auth/login").with(randomRemoteAddress())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(user.email(), FIXTURE_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(user.id().toString()))
                .andExpect(jsonPath("$.user.hasPassword").value(true))
                .andExpect(jsonPath("$.user.linkedProviders", contains("GOOGLE")))
                .andReturn());

        deleteGoogleLink(JsonPath.read(json, "$.accessToken")).andExpect(status().isNoContent());
    }

    @Test
    void googleOnlyAccount_resetConfirmRejectsPasswordsOutsideSharedPolicy() throws Exception {
        GoogleUser user = signInWithGoogle(googleTokens.idToken());
        String rawToken = OpaqueTokens.generate();
        jdbcTemplate.update(
                "insert into password_reset_tokens (id, user_id, token_hash, expires_at, created_at, updated_at) "
                        + "values (?, ?, ?, ?, now(), now())",
                UUID.randomUUID(), user.id(), OpaqueTokens.sha256Hex(rawToken),
                OffsetDateTime.ofInstant(clock.instant().plus(Duration.ofMinutes(30)), ZoneOffset.UTC));

        // 7 ký tự; và 37 ký tự "é" = 74 byte UTF-8 (dưới 72 ký tự nhưng quá 72 byte): cả hai → 400.
        for (String tooWeakOrLong : new String[] {"abcdefg", "é".repeat(37)}) {
            mockMvc.perform(post("/api/v1/auth/password-reset/confirm").with(randomRemoteAddress())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"token": "%s", "newPassword": "%s"}
                                    """.formatted(rawToken, tooWeakOrLong)))
                    .andExpect(status().isBadRequest());
        }
        assertThat(jdbcTemplate.queryForObject(
                "select used_at from password_reset_tokens where user_id = ?", Object.class, user.id())).isNull();

        // Token chưa bị tiêu thụ: mật khẩu hợp lệ vẫn dùng được.
        mockMvc.perform(post("/api/v1/auth/password-reset/confirm").with(randomRemoteAddress())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token": "%s", "newPassword": "%s"}
                                """.formatted(rawToken, FIXTURE_PASSWORD)))
                .andExpect(status().is2xxSuccessful());
    }
}
