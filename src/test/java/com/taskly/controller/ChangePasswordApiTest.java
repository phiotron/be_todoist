package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class ChangePasswordApiTest extends IntegrationTestBase {

    /** Mật khẩu fixture thứ hai cho test tự động — không phải mật khẩu thật của ai. */
    private static final String NEW_FIXTURE_PASSWORD = "Fixture-New-Pass-456";

    private static MockHttpServletRequestBuilder changePassword(String accessToken, String current, String next) {
        return post("/api/v1/me/password").header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"currentPassword": "%s", "newPassword": "%s"}
                        """.formatted(current, next));
    }

    private static MockHttpServletRequestBuilder login(String email, String password) {
        return post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email": "%s", "password": "%s"}
                """.formatted(email, password));
    }

    private static MockHttpServletRequestBuilder refresh(String refreshToken) {
        return post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("""
                {"refreshToken": "%s"}
                """.formatted(refreshToken));
    }

    private int activeRefreshTokens(UUID userId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from refresh_tokens where user_id = ? and revoked_at is null", Integer.class, userId);
    }

    private String passwordHash(UUID userId) {
        return jdbcTemplate.queryForObject("select password_hash from users where id = ?", String.class, userId);
    }

    @Test
    void change_correctCurrentPassword_returnsNewPairAndRevokesEveryOldRefreshToken() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String otherDevice = JsonPath.read(body(mockMvc.perform(login(user.email(), FIXTURE_PASSWORD))
                .andExpect(status().isOk()).andReturn()), "$.refreshToken");

        MvcResult result = mockMvc.perform(changePassword(user.accessToken(), FIXTURE_PASSWORD, NEW_FIXTURE_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken", matchesPattern("[A-Za-z0-9_-]{43}")))
                .andExpect(jsonPath("$.accessTokenExpiresIn").value(900))
                .andExpect(jsonPath("$.user.id").value(user.id().toString()))
                .andExpect(content().string(not(containsString("password"))))
                .andReturn();
        String newAccess = JsonPath.read(body(result), "$.accessToken");
        String newRefresh = JsonPath.read(body(result), "$.refreshToken");

        assertThat(activeRefreshTokens(user.id())).as("chỉ còn refresh token vừa phát").isEqualTo(1);
        mockMvc.perform(refresh(user.refreshToken())).andExpect(status().isUnauthorized());
        mockMvc.perform(refresh(otherDevice)).andExpect(status().isUnauthorized());
        mockMvc.perform(refresh(newRefresh)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(newAccess)))
                .andExpect(status().isOk());
    }

    @Test
    void change_afterwardsLoginNeedsTheNewPassword() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(changePassword(user.accessToken(), FIXTURE_PASSWORD, NEW_FIXTURE_PASSWORD))
                .andExpect(status().isOk());

        mockMvc.perform(login(user.email(), FIXTURE_PASSWORD)).andExpect(status().isUnauthorized());
        mockMvc.perform(login(user.email(), NEW_FIXTURE_PASSWORD)).andExpect(status().isOk());
    }

    @Test
    void change_wrongCurrentPassword_returns400AndChangesNothing() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String hashBefore = passwordHash(user.id());

        mockMvc.perform(changePassword(user.accessToken(), "Wrong-Pass-999", NEW_FIXTURE_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Dữ liệu không hợp lệ"))
                .andExpect(jsonPath("$.detail").value("Mật khẩu hiện tại không đúng"))
                .andExpect(jsonPath("$.errors").doesNotExist());

        assertThat(passwordHash(user.id())).isEqualTo(hashBefore);
        assertThat(activeRefreshTokens(user.id())).isEqualTo(1);
        mockMvc.perform(login(user.email(), FIXTURE_PASSWORD)).andExpect(status().isOk());
    }

    @Test
    void change_currentPasswordOverBcryptLimit_returns400NotServerError() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(changePassword(user.accessToken(), "ạ".repeat(30), NEW_FIXTURE_PASSWORD)) // 90 byte UTF-8
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Mật khẩu hiện tại không đúng"));
    }

    @Test
    void change_invalidOrMissingFields_returns400WithErrors() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(changePassword(user.accessToken(), FIXTURE_PASSWORD, "ngan"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'newPassword')].message",
                        contains("Mật khẩu phải từ 8 đến 72 ký tự")));
        mockMvc.perform(post("/api/v1/me/password").header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("currentPassword", "newPassword")))
                .andExpect(jsonPath("$.errors[?(@.field == 'currentPassword')].message",
                        contains("Vui lòng nhập mật khẩu hiện tại")))
                .andExpect(jsonPath("$.errors[?(@.field == 'newPassword')].message",
                        contains("Vui lòng nhập mật khẩu mới")));
    }

    @Test
    void change_wrongCurrentPassword_detailInEnglish() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(changePassword(user.accessToken(), "Wrong-Pass-999", NEW_FIXTURE_PASSWORD)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid data"))
                .andExpect(jsonPath("$.detail").value("Your current password is incorrect"));
    }

    @Test
    void change_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/me/password").contentType(MediaType.APPLICATION_JSON).content("""
                        {"currentPassword": "%s", "newPassword": "%s"}
                        """.formatted(FIXTURE_PASSWORD, NEW_FIXTURE_PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void change_oldAccessTokenKeepsWorkingUntilItExpires() throws Exception {
        // JWT stateless: chỉ refresh token bị thu hồi; access token cũ sống tối đa 15 phút (ghi ở "Quyết định").
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(changePassword(user.accessToken(), FIXTURE_PASSWORD, NEW_FIXTURE_PASSWORD))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isOk());
    }
}
