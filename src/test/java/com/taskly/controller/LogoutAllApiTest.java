package com.taskly.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** POST me/logout-all: thu hồi mọi refresh token của user (spec 12.1), không đụng user khác. */
class LogoutAllApiTest extends IntegrationTestBase {

    private static final String LOGOUT_ALL = "/api/v1/me/logout-all";

    private String loginRefreshToken(String email) throws Exception {
        return JsonPath.read(body(mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(email, FIXTURE_PASSWORD)))
                .andExpect(status().isOk()).andReturn()), "$.refreshToken");
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"refreshToken": "%s"}
                        """.formatted(refreshToken)));
    }

    @Test
    void logoutAll_revokesEveryRefreshTokenOfThatUserOnly() throws Exception {
        RegisteredUser alice = registerUser(uniqueEmail());
        String secondDevice = loginRefreshToken(alice.email());
        RegisteredUser bob = registerUser(uniqueEmail());

        mockMvc.perform(post(LOGOUT_ALL).header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken())))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        refresh(alice.refreshToken()).andExpect(status().isUnauthorized());
        refresh(secondDevice).andExpect(status().isUnauthorized());
        refresh(bob.refreshToken()).andExpect(status().isOk());
        // Access token đang cầm vẫn sống tới khi hết hạn (JWT stateless, spec 5.9 / Quyết định #13).
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken())))
                .andExpect(status().isOk());
    }

    @Test
    void logoutAll_isIdempotent_andLoggingInAgainWorks() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(post(LOGOUT_ALL).header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isNoContent());
        mockMvc.perform(post(LOGOUT_ALL).header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isNoContent());

        refresh(loginRefreshToken(user.email())).andExpect(status().isOk());
    }

    @Test
    void logoutAll_withoutToken_returns401() throws Exception {
        mockMvc.perform(post(LOGOUT_ALL))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
