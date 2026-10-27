package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.integration.OutgoingMail;
import com.taskly.security.OpaqueTokens;
import com.taskly.support.PasswordResetMails;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Quên mật khẩu: tối đa 5 lần / giờ / email; vượt vẫn 200 như nhau nhưng không gửi thư (spec mục 5.9; người dùng chưa chốt
 * 200 hay 429 — giữ 200 như plan). Bộ đếm (PasswordResetRateLimiter) được xoá trước mỗi test bởi
 * IntegrationTestBase.resetRateLimits().
 */
class PasswordResetRateLimitTest extends IntegrationTestBase {

    private MvcResult requestReset(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/password-reset/request").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
    }

    private List<String> usableTokenHashes(UUID userId) {
        Instant now = clock.instant();
        return jdbcTemplate.queryForList(
                        "select token_hash, expires_at, used_at from password_reset_tokens where user_id = ?", userId)
                .stream()
                .filter(row -> row.get("used_at") == null
                        && ((Timestamp) row.get("expires_at")).toInstant().isAfter(now))
                .map(row -> (String) row.get("token_hash"))
                .toList();
    }

    @Test
    void sixthRequestWithinAnHour_returns200ButSendsNoMailAndKeepsFifthToken() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        for (int i = 0; i < 5; i++) {
            requestReset(user.email());
        }
        List<OutgoingMail> mails = mailbox.sentTo(user.email());
        assertThat(mails).hasSize(5);
        String fifthHash = OpaqueTokens.sha256Hex(PasswordResetMails.tokenFrom(mails.get(4)));

        MvcResult sixth = requestReset(user.email());

        assertThat(body(sixth)).isEmpty();
        assertThat(mailbox.sentTo(user.email())).hasSize(5);
        assertThat(usableTokenHashes(user.id())).containsExactly(fifthHash);
    }

    @Test
    void overTheLimit_unknownEmailLooksExactlyLikeKnownEmail() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String unknown = uniqueEmail();
        MvcResult known = null;
        MvcResult missing = null;
        for (int i = 0; i < 6; i++) {
            known = requestReset(user.email());
            missing = requestReset(unknown);
        }

        assertThat(body(missing)).isEqualTo(body(known)).isEmpty();
        assertThat(Set.copyOf(missing.getResponse().getHeaderNames()))
                .isEqualTo(Set.copyOf(known.getResponse().getHeaderNames()));
    }

    @Test
    void limitIsCountedPerNormalizedEmail() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String upper = user.email().toUpperCase();
        for (String variant : List.of(user.email(), upper, " " + user.email() + " ", upper, user.email())) {
            requestReset(variant);
        }

        requestReset("  " + upper); // lần thứ 6 của cùng một email sau khi chuẩn hoá

        assertThat(mailbox.sentTo(user.email())).hasSize(5);
    }

    @Test
    void otherEmailsAreNotAffected() throws Exception {
        RegisteredUser first = registerUser(uniqueEmail());
        RegisteredUser second = registerUser(uniqueEmail());
        for (int i = 0; i < 6; i++) {
            requestReset(first.email());
        }

        requestReset(second.email());

        assertThat(mailbox.sentTo(first.email())).hasSize(5);
        assertThat(mailbox.sentTo(second.email())).hasSize(1);
    }

    @Test
    void afterTheWindow_requestsAreAllowedAgain() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        for (int i = 0; i < 6; i++) {
            requestReset(user.email());
        }
        assertThat(mailbox.sentTo(user.email())).hasSize(5);
        clock.advance(Duration.ofMinutes(61)); // limiter đọc giờ từ bean Clock = MutableClock

        requestReset(user.email());

        assertThat(mailbox.sentTo(user.email())).hasSize(6);
    }
}
