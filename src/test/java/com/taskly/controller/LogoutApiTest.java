package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.security.OpaqueTokens;
import java.sql.Timestamp;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class LogoutApiTest extends IntegrationTestBase {

    private static MockHttpServletRequestBuilder jsonPost(String path, String refreshToken) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content("""
                {"refreshToken": "%s"}
                """.formatted(refreshToken));
    }

    private ResultActions logout(String accessToken, String refreshToken) throws Exception {
        MockHttpServletRequestBuilder request = jsonPost("/api/v1/auth/logout", refreshToken);
        if (accessToken != null) {
            request.header(HttpHeaders.AUTHORIZATION, bearer(accessToken));
        }
        return mockMvc.perform(request);
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(jsonPost("/api/v1/auth/refresh", refreshToken));
    }

    @Test
    void logout_revokesRefreshToken() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        logout(user.accessToken(), user.refreshToken()).andExpect(status().isNoContent());

        refresh(user.refreshToken()).andExpect(status().isUnauthorized());
    }

    @Test
    void logout_withoutJwt_returns401ProblemDetail() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        logout(null, user.refreshToken())
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        refresh(user.refreshToken()).andExpect(status().isOk()); // token chưa bị thu hồi
    }

    @Test
    void logout_withAnotherUsersRefreshToken_isIgnored() throws Exception {
        RegisteredUser alice = registerUser(uniqueEmail());
        RegisteredUser bob = registerUser(uniqueEmail());

        logout(alice.accessToken(), bob.refreshToken()).andExpect(status().isNoContent());

        refresh(bob.refreshToken()).andExpect(status().isOk());
    }

    @Test
    void logout_unknownToken_returns204() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        logout(user.accessToken(), OpaqueTokens.generate()).andExpect(status().isNoContent());
    }

    private Timestamp revokedAtOf(String refreshToken) {
        return jdbcTemplate.queryForObject(
                "select revoked_at from refresh_tokens where token_hash = ?",
                Timestamp.class, OpaqueTokens.sha256Hex(refreshToken));
    }

    @Test
    void logout_setsRevokedAt_andSecondLogoutIsIdempotent204() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        assertThat(revokedAtOf(user.refreshToken())).as("trước logout").isNull();

        logout(user.accessToken(), user.refreshToken()).andExpect(status().isNoContent());
        Timestamp firstRevokedAt = revokedAtOf(user.refreshToken());
        assertThat(firstRevokedAt).as("revoked_at sau logout lần 1").isNotNull();

        clock.advance(Duration.ofMinutes(1));
        logout(user.accessToken(), user.refreshToken()).andExpect(status().isNoContent());

        assertThat(revokedAtOf(user.refreshToken()))
                .as("logout lần 2 không ghi đè thời điểm thu hồi đầu tiên")
                .isEqualTo(firstRevokedAt);
    }

    @Test
    void logout_withAnotherUsersRefreshToken_leavesItUnrevokedInDb() throws Exception {
        RegisteredUser alice = registerUser(uniqueEmail());
        RegisteredUser bob = registerUser(uniqueEmail());

        logout(alice.accessToken(), bob.refreshToken()).andExpect(status().isNoContent());

        assertThat(revokedAtOf(bob.refreshToken())).as("token của bob vẫn chưa bị thu hồi").isNull();
    }

    @Test
    void logout_emptyBody_returns400WithRefreshTokenError() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("refreshToken")))
                .andExpect(jsonPath("$.errors[?(@.field == 'refreshToken')].message",
                        contains("Thiếu refresh token")));
    }

    @Test
    void logout_withGarbageJwt_returns401ProblemDetail() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        logout("abc", user.refreshToken())
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        assertThat(revokedAtOf(user.refreshToken())).as("token không bị thu hồi").isNull();
    }

    /** Lệch có chủ đích: JWT stateless, access token vẫn dùng được tới khi hết hạn dù đã logout. */
    @Test
    void logout_doesNotInvalidateAccessToken_untilItExpires() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        logout(user.accessToken(), user.refreshToken()).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isOk());
    }
}
