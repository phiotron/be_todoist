package com.taskly.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** GET me và AuthResponse.user có twoFactorEnabled (spec 12.1, M3): true chỉ khi user_totp đã bật. */
class MeTwoFactorFieldApiTest extends IntegrationTestBase {

    private void insertTotp(UUID userId, boolean enabled) {
        jdbcTemplate.update(
                "insert into user_totp (user_id, secret_encrypted, enabled_at, created_at, updated_at) "
                        + "values (?, 'ban-ma-gia', " + (enabled ? "now()" : "null") + ", now(), now())",
                userId);
    }

    @Test
    void newUser_twoFactorEnabledFalse_inRegisterLoginAndMe() throws Exception {
        String email = uniqueEmail();
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s", "displayName": "Người thử"}
                                """.formatted(email, FIXTURE_PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.twoFactorEnabled").value(false));
        String accessToken = com.jayway.jsonpath.JsonPath.read(body(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(email, FIXTURE_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.twoFactorEnabled").value(false))
                .andReturn()), "$.accessToken");

        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.twoFactorEnabled").value(false));
    }

    @Test
    void pendingSetupIsNotEnabled_enabledRowIs() throws Exception {
        RegisteredUser pending = registerUser(uniqueEmail());
        RegisteredUser enabled = registerUser(uniqueEmail());
        insertTotp(pending.id(), false);
        insertTotp(enabled.id(), true);

        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(pending.accessToken())))
                .andExpect(jsonPath("$.twoFactorEnabled").value(false));
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(enabled.accessToken())))
                .andExpect(jsonPath("$.twoFactorEnabled").value(true));
    }
}
