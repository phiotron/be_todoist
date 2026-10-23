package com.taskly.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.security.OpaqueTokens;
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
}
