package com.taskly.controller;

import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.integration.OutgoingMail;
import com.taskly.security.OpaqueTokens;
import com.taskly.support.PasswordResetMails;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class PasswordResetRequestApiTest extends IntegrationTestBase {

    private static MockHttpServletRequestBuilder requestReset(String email) {
        return post("/api/v1/auth/password-reset/request").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s"}
                        """.formatted(email));
    }

    private List<Map<String, Object>> resetTokensOf(UUID userId) {
        return jdbcTemplate.queryForList(
                "select token_hash, expires_at, used_at, created_at from password_reset_tokens "
                        + "where user_id = ? order by created_at",
                userId);
    }

    private static Instant instant(Object timestamp) {
        return ((Timestamp) timestamp).toInstant();
    }

    @Test
    void request_existingEmail_sendsMailWithLinkAndCode_andStoresOnlyHash() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(requestReset("  " + user.email().toUpperCase() + " "))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        List<OutgoingMail> mails = mailbox.sentTo(user.email());
        assertThat(mails).hasSize(1);
        OutgoingMail mail = mails.get(0);
        String token = PasswordResetMails.tokenFrom(mail);
        assertThat(mail.subject()).isEqualTo("Taskly - Đặt lại mật khẩu");
        assertThat(mail.body())
                .startsWith("Xin chào Người dùng thử,")
                .contains("taskly://reset-password/" + token)
                .contains("\n" + token + "\n") // mã đứng riêng một dòng để dán vào app
                .contains("30 phút");

        List<Map<String, Object>> tokens = resetTokensOf(user.id());
        assertThat(tokens).hasSize(1);
        Map<String, Object> row = tokens.get(0);
        assertThat(row.get("token_hash")).isEqualTo(OpaqueTokens.sha256Hex(token)).isNotEqualTo(token);
        assertThat(row.get("used_at")).isNull();
        assertThat(Duration.between(instant(row.get("created_at")), instant(row.get("expires_at"))))
                .isCloseTo(Duration.ofMinutes(30), Duration.ofSeconds(5));
    }

    @Test
    void request_unknownEmail_returnsIdenticalResponseAndSendsNothing() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String unknown = uniqueEmail();

        MvcResult known = mockMvc.perform(requestReset(user.email())).andExpect(status().isOk()).andReturn();
        MvcResult missing = mockMvc.perform(requestReset(unknown)).andExpect(status().isOk()).andReturn();

        assertThat(body(missing)).isEqualTo(body(known)).isEmpty();
        assertThat(Set.copyOf(missing.getResponse().getHeaderNames()))
                .isEqualTo(Set.copyOf(known.getResponse().getHeaderNames()));
        assertThat(mailbox.sentTo(unknown)).isEmpty();
        assertThat(mailbox.sentTo(user.email())).hasSize(1);
    }

    @Test
    void request_englishUser_getsEnglishMail() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        jdbcTemplate.update("update users set locale = 'en' where id = ?", user.id());

        mockMvc.perform(requestReset(user.email())).andExpect(status().isOk());

        OutgoingMail mail = mailbox.sentTo(user.email()).get(0);
        assertThat(mail.subject()).isEqualTo("Taskly - Reset your password");
        assertThat(mail.body())
                .startsWith("Hello Người dùng thử,")
                .contains("taskly://reset-password/" + PasswordResetMails.tokenFrom(mail))
                .contains("expires in 30 minutes");
    }

    @Test
    void request_again_invalidatesPreviousToken() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(requestReset(user.email())).andExpect(status().isOk());
        mockMvc.perform(requestReset(user.email())).andExpect(status().isOk());

        List<OutgoingMail> mails = mailbox.sentTo(user.email());
        assertThat(mails).hasSize(2);
        String firstHash = OpaqueTokens.sha256Hex(PasswordResetMails.tokenFrom(mails.get(0)));
        String secondHash = OpaqueTokens.sha256Hex(PasswordResetMails.tokenFrom(mails.get(1)));
        Map<Object, Instant> expiry = resetTokensOf(user.id()).stream()
                .collect(toMap(row -> row.get("token_hash"), row -> instant(row.get("expires_at"))));
        Instant now = clock.instant();
        assertThat(expiry.get(firstHash)).isBeforeOrEqualTo(now);
        assertThat(expiry.get(secondHash)).isAfter(now);
    }

    @Test
    void request_mailFailure_returns503AndKeepsNoToken_thenRetryWorks() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        mailbox.setFailing(true);

        mockMvc.perform(requestReset(user.email()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Dịch vụ tạm thời không khả dụng"))
                .andExpect(jsonPath("$.detail").value("Chưa gửi được email lúc này, vui lòng thử lại sau"));
        assertThat(resetTokensOf(user.id())).isEmpty();

        mailbox.setFailing(false); // app cho thử lại
        mockMvc.perform(requestReset(user.email())).andExpect(status().isOk());
        assertThat(resetTokensOf(user.id())).hasSize(1);
        assertThat(mailbox.sentTo(user.email())).hasSize(1);
    }

    @Test
    void request_mailFailure_keepsPreviousTokenUsable() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        mockMvc.perform(requestReset(user.email())).andExpect(status().isOk());
        mailbox.setFailing(true);

        mockMvc.perform(requestReset(user.email())).andExpect(status().isServiceUnavailable());

        List<Map<String, Object>> tokens = resetTokensOf(user.id());
        assertThat(tokens).hasSize(1);
        assertThat(instant(tokens.get(0).get("expires_at"))).isAfter(clock.instant());
    }

    @Test
    void request_mailFailure_problemInEnglish() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        mailbox.setFailing(true);

        mockMvc.perform(requestReset(user.email()).header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Service temporarily unavailable"))
                .andExpect(jsonPath("$.detail").value("We could not send the email right now, please try again later"));
    }

    @Test
    void request_unknownEmailWhileMailIsDown_stillReturns200() throws Exception {
        // Spec mục 17: chấp nhận khác biệt này ở vòng 1 (email có thật → 503, không có → 200).
        mailbox.setFailing(true);

        mockMvc.perform(requestReset(uniqueEmail())).andExpect(status().isOk());
    }

    @Test
    void request_invalidOrMissingEmail_returns400() throws Exception {
        mockMvc.perform(requestReset("khong-phai-email"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'email')].message", contains("Email không hợp lệ")));
        mockMvc.perform(post("/api/v1/auth/password-reset/request").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'email')].message", contains("Vui lòng nhập email")));
    }

    @Test
    void request_isPublicAndIgnoresStaleBearerHeader() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(requestReset(user.email()).header(HttpHeaders.AUTHORIZATION, bearer("not-a-jwt")))
                .andExpect(status().isOk());
        assertThat(mailbox.sentTo(user.email())).hasSize(1);
    }
}
