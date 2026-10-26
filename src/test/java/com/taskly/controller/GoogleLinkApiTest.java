package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.GoogleIntegrationTestBase;
import com.taskly.support.GoogleTestTokens;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** POST /api/v1/me/identities/google {idToken} — liên kết từ Cài đặt (spec 12.1, tình huống 3). */
class GoogleLinkApiTest extends GoogleIntegrationTestBase {

    @Test
    void passwordAccount_linksGoogle_thenGoogleSignInOpensSameAccount() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        GoogleTestTokens.IdToken token = googleTokens.idToken(); // email Google khác email tài khoản: được phép

        postLinkGoogle(user.accessToken(), idTokenJson(token.sign()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(jsonPath("$.hasPassword").value(true))
                .andExpect(jsonPath("$.linkedProviders", contains("GOOGLE")));
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select user_id, email from user_identities where subject = ?", token.getSubject());
        assertThat(row).containsEntry("user_id", user.id()).containsEntry("email", token.getEmail());
        postGoogleSignIn(googleTokens.idToken().subject(token.getSubject()).sign())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(user.id().toString()));
    }

    @Test
    void linkingSameGoogleAccountAgain_isIdempotent() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String subject = GoogleTestTokens.uniqueSubject();

        postLinkGoogle(user.accessToken(), idTokenJson(googleTokens.idToken().subject(subject).sign()))
                .andExpect(status().isNoContent());
        postLinkGoogle(user.accessToken(), idTokenJson(googleTokens.idToken().subject(subject).sign()))
                .andExpect(status().isNoContent());

        assertThat(countIdentities(subject)).isEqualTo(1);
    }

    @Test
    void googleAccountOfAnotherUser_returns409() throws Exception {
        GoogleUser owner = signInWithGoogle(googleTokens.idToken());
        RegisteredUser other = registerUser(uniqueEmail());

        postLinkGoogle(other.accessToken(), idTokenJson(googleTokens.idToken().subject(owner.subject()).sign()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Tài khoản Google này đã liên kết với một tài khoản Taskly khác"));

        assertThat(jdbcTemplate.queryForObject(
                "select user_id from user_identities where subject = ?", UUID.class, owner.subject()))
                .isEqualTo(owner.id());
    }

    @Test
    void accountAlreadyLinkedToAnotherGoogle_returns409() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        GoogleTestTokens.IdToken first = googleTokens.idToken();
        postLinkGoogle(user.accessToken(), idTokenJson(first.sign())).andExpect(status().isNoContent());
        GoogleTestTokens.IdToken second = googleTokens.idToken();

        postLinkGoogle(user.accessToken(), idTokenJson(second.sign()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        "Tài khoản của bạn đã liên kết với một tài khoản Google khác. Hãy bỏ liên kết trước"));

        assertThat(countIdentities(first.getSubject())).isEqualTo(1);
        assertThat(countIdentities(second.getSubject())).isZero();
    }

    @Test
    void unverifiedGoogleEmail_returns403() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        GoogleTestTokens.IdToken token = googleTokens.idToken().emailVerified(false);

        postLinkGoogle(user.accessToken(), idTokenJson(token.sign()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Email Google chưa được xác minh"));

        assertThat(countIdentities(token.getSubject())).isZero();
    }

    @Test
    void invalidIdToken_returns400NotUnauthorized() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        for (String invalid : new String[] {
                googleTokens.idToken().signedByUntrustedKey().sign(),
                googleTokens.idToken().lifetime(Duration.ofMinutes(-5)).sign(),
                googleTokens.idToken().audience("other-app.apps.googleusercontent.com").sign()}) {
            postLinkGoogle(user.accessToken(), idTokenJson(invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                    .andExpect(jsonPath("$.detail").value("Mã xác thực Google không hợp lệ hoặc đã hết hạn"));
        }
    }

    @Test
    void mustSendExactlyOneToken() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String idToken = googleTokens.idToken().sign();

        for (String json : new String[] {"{}", """
                {"idToken": "%s", "linkToken": "x"}
                """.formatted(idToken)}) {
            postLinkGoogle(user.accessToken(), json)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("Cần gửi đúng một trong hai: idToken hoặc linkToken"));
        }
    }

    @Test
    void withoutAccessToken_returns401() throws Exception {
        mockMvc.perform(post(GOOGLE_IDENTITY).contentType(MediaType.APPLICATION_JSON)
                        .content(idTokenJson(googleTokens.idToken().sign())))
                .andExpect(status().isUnauthorized());
    }
}
