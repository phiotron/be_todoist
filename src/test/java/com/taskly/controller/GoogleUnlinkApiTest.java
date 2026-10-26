package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.GoogleIntegrationTestBase;
import com.taskly.support.GoogleTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** DELETE /api/v1/me/identities/google — bỏ liên kết (204); chưa liên kết → 404; chưa có mật khẩu → 409. */
class GoogleUnlinkApiTest extends GoogleIntegrationTestBase {

    @Test
    void passwordAccountWithGoogle_unlinks204_andGoogleNoLongerOpensIt() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        GoogleTestTokens.IdToken token = googleTokens.idToken();
        postLinkGoogle(user.accessToken(), idTokenJson(token.sign())).andExpect(status().isNoContent());

        deleteGoogleLink(user.accessToken())
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(countIdentities(token.getSubject())).isZero();
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(jsonPath("$.linkedProviders").isEmpty());
        // Google đó giờ là danh tính mới: đăng nhập tạo tài khoản khác, không mở tài khoản cũ.
        postGoogleSignIn(googleTokens.idToken().subject(token.getSubject()).sign())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id", not(user.id().toString())));
    }

    @Test
    void googleOnlyAccount_unlinkReturns409AndKeepsLink() throws Exception {
        GoogleUser user = signInWithGoogle(googleTokens.idToken());

        deleteGoogleLink(user.accessToken())
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Hãy đặt mật khẩu trước khi bỏ liên kết Google"));

        assertThat(countIdentities(user.subject())).isEqualTo(1);
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(jsonPath("$.linkedProviders", contains("GOOGLE")));
    }

    @Test
    void noGoogleLinked_unlinkReturns404() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        deleteGoogleLink(user.accessToken())
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Tài khoản chưa liên kết Google"));
    }

    @Test
    void unlinkTwice_secondCallReturns404() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        postLinkGoogle(user.accessToken(), idTokenJson(googleTokens.idToken().sign())).andExpect(status().isNoContent());

        deleteGoogleLink(user.accessToken()).andExpect(status().isNoContent());
        deleteGoogleLink(user.accessToken()).andExpect(status().isNotFound());
    }

    @Test
    void withoutAccessToken_returns401() throws Exception {
        mockMvc.perform(delete(GOOGLE_IDENTITY)).andExpect(status().isUnauthorized());
    }
}
