package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.integration.OutgoingMail;
import com.taskly.security.OpaqueTokens;
import com.taskly.support.PasswordResetMails;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class PasswordResetConfirmApiTest extends IntegrationTestBase {

    /** Mật khẩu fixture thứ hai cho test tự động — không phải mật khẩu thật của ai. */
    private static final String NEW_FIXTURE_PASSWORD = "Fixture-New-Pass-456";

    private static MockHttpServletRequestBuilder confirm(String token, String newPassword) {
        return post("/api/v1/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token": "%s", "newPassword": "%s"}
                        """.formatted(token, newPassword));
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

    /** Gọi password-reset/request rồi lấy token từ thư mới nhất gửi tới email. */
    private String issueResetToken(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/password-reset/request").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s"}
                                """.formatted(email)))
                .andExpect(status().isOk());
        List<OutgoingMail> mails = mailbox.sentTo(email);
        return PasswordResetMails.tokenFrom(mails.get(mails.size() - 1));
    }

    @Test
    void confirm_validToken_changesPasswordRevokesSessionsAndDoesNotLogIn() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String token = issueResetToken(user.email());

        mockMvc.perform(confirm(token, NEW_FIXTURE_PASSWORD))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        mockMvc.perform(refresh(user.refreshToken())).andExpect(status().isUnauthorized());
        mockMvc.perform(login(user.email(), FIXTURE_PASSWORD)).andExpect(status().isUnauthorized());
        mockMvc.perform(login(user.email(), NEW_FIXTURE_PASSWORD)).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "select used_at is not null from password_reset_tokens where token_hash = ?",
                Boolean.class, OpaqueTokens.sha256Hex(token))).isTrue();
    }

    @Test
    void confirm_sameTokenTwice_returns410() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String token = issueResetToken(user.email());
        mockMvc.perform(confirm(token, NEW_FIXTURE_PASSWORD)).andExpect(status().isNoContent());

        mockMvc.perform(confirm(token, "Fixture-Other-Pass-789"))
                .andExpect(status().isGone())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Không còn hiệu lực"))
                .andExpect(jsonPath("$.detail").value("Mã đặt lại mật khẩu đã hết hạn hoặc đã được dùng"));
        mockMvc.perform(login(user.email(), NEW_FIXTURE_PASSWORD)).andExpect(status().isOk());
    }

    @Test
    void confirm_unknownToken_returns404() throws Exception {
        mockMvc.perform(confirm(OpaqueTokens.generate(), NEW_FIXTURE_PASSWORD))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Không tìm thấy"))
                .andExpect(jsonPath("$.detail").value("Mã đặt lại mật khẩu không đúng"));
    }

    @Test
    void confirm_thirtyMinutesLater_returns410AndKeepsOldPassword() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String token = issueResetToken(user.email());
        clock.advance(Duration.ofMinutes(30));

        mockMvc.perform(confirm(token, NEW_FIXTURE_PASSWORD)).andExpect(status().isGone());
        mockMvc.perform(login(user.email(), FIXTURE_PASSWORD)).andExpect(status().isOk());
    }

    @Test
    void confirm_29MinutesLater_succeeds() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String token = issueResetToken(user.email());
        clock.advance(Duration.ofMinutes(29));

        mockMvc.perform(confirm(token, NEW_FIXTURE_PASSWORD)).andExpect(status().isNoContent());
    }

    @Test
    void confirm_tokenSupersededByNewerRequest_returns410() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String first = issueResetToken(user.email());
        String second = issueResetToken(user.email());

        mockMvc.perform(confirm(first, NEW_FIXTURE_PASSWORD)).andExpect(status().isGone());
        mockMvc.perform(confirm(second, NEW_FIXTURE_PASSWORD)).andExpect(status().isNoContent());
    }

    @Test
    void confirm_codePastedWithSurroundingWhitespace_isAccepted() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String token = issueResetToken(user.email());

        mockMvc.perform(confirm("  " + token + "\\n", NEW_FIXTURE_PASSWORD)) // JSON "\n" = xuống dòng khi dán
                .andExpect(status().isNoContent());
    }

    @Test
    void confirm_invalidNewPassword_returns400AndTokenStaysUsable() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String token = issueResetToken(user.email());

        mockMvc.perform(confirm(token, "ngan"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'newPassword')].message",
                        contains("Mật khẩu phải từ 8 đến 72 ký tự")));
        mockMvc.perform(confirm(token, NEW_FIXTURE_PASSWORD)).andExpect(status().isNoContent());
    }

    @Test
    void confirm_missingFields_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("token", "newPassword")))
                .andExpect(jsonPath("$.errors[?(@.field == 'token')].message",
                        contains("Vui lòng nhập mã đặt lại mật khẩu")))
                .andExpect(jsonPath("$.errors[?(@.field == 'newPassword')].message",
                        contains("Vui lòng nhập mật khẩu mới")));
    }

    @Test
    void confirm_errorsInEnglish() throws Exception {
        mockMvc.perform(confirm(OpaqueTokens.generate(), NEW_FIXTURE_PASSWORD).header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not found"))
                .andExpect(jsonPath("$.detail").value("The password reset code is incorrect"));

        RegisteredUser user = registerUser(uniqueEmail());
        String token = issueResetToken(user.email());
        mockMvc.perform(confirm(token, NEW_FIXTURE_PASSWORD)).andExpect(status().isNoContent());
        mockMvc.perform(confirm(token, NEW_FIXTURE_PASSWORD).header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.title").value("No longer valid"))
                .andExpect(jsonPath("$.detail").value("The password reset code has expired or has already been used"));
    }

    @Test
    void confirm_isPublicAndIgnoresStaleBearerHeader() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String token = issueResetToken(user.email());

        mockMvc.perform(confirm(token, NEW_FIXTURE_PASSWORD).header(HttpHeaders.AUTHORIZATION, bearer("not-a-jwt")))
                .andExpect(status().isNoContent());
    }
}
