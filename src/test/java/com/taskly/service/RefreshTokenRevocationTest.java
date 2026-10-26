package com.taskly.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.IntegrationTestBase;
import com.taskly.security.OpaqueTokens;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** AuthService.revokeAllRefreshTokens: dùng khi đổi / đặt lại mật khẩu (spec mục 12.1). */
class RefreshTokenRevocationTest extends IntegrationTestBase {

    @Autowired
    private AuthService authService;

    private String login(String email) throws Exception {
        return JsonPath.read(body(mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(email, FIXTURE_PASSWORD)))
                .andExpect(status().isOk()).andReturn()), "$.refreshToken");
    }

    private String refresh(String refreshToken) throws Exception {
        return JsonPath.read(body(mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isOk()).andReturn()), "$.refreshToken");
    }

    private Timestamp revokedAt(String refreshToken) {
        return jdbcTemplate.queryForObject("select revoked_at from refresh_tokens where token_hash = ?",
                Timestamp.class, OpaqueTokens.sha256Hex(refreshToken));
    }

    private int activeTokens(UUID userId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from refresh_tokens where user_id = ? and revoked_at is null", Integer.class, userId);
    }

    @Test
    void revokeAllRefreshTokens_revokesEveryActiveTokenOfThatUserOnly() throws Exception {
        RegisteredUser alice = registerUser(uniqueEmail());
        String secondDevice = login(alice.email());
        String rotated = refresh(alice.refreshToken()); // token lúc đăng ký bị thu hồi khi xoay vòng
        Timestamp revokedByRotation = revokedAt(alice.refreshToken());
        RegisteredUser bob = registerUser(uniqueEmail());
        clock.advance(Duration.ofMinutes(5));

        authService.revokeAllRefreshTokens(alice.id());

        assertThat(activeTokens(alice.id())).isZero();
        assertThat(revokedAt(secondDevice)).isNotNull();
        assertThat(revokedAt(rotated)).isNotNull();
        assertThat(revokedAt(alice.refreshToken())).as("không ghi đè revoked_at cũ").isEqualTo(revokedByRotation);
        assertThat(activeTokens(bob.id())).isEqualTo(1);
    }
}
