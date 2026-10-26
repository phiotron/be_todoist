package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.GoogleIntegrationTestBase;
import com.taskly.security.GoogleIdentity;
import com.taskly.security.GoogleLinkTokens;
import com.taskly.support.GoogleTestTokens;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

/** Luồng 409 link-required → đăng nhập mật khẩu → POST me/identities/google {linkToken} (spec 12.1, tình huống 2). */
class GoogleLinkTokenFlowApiTest extends GoogleIntegrationTestBase {

    @Autowired
    private GoogleLinkTokens googleLinkTokens;

    private String requestLinkToken(GoogleTestTokens.IdToken token) throws Exception {
        return JsonPath.read(body(postGoogleSignIn(token.sign())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("link-required"))
                .andReturn()), "$.linkToken");
    }

    @Test
    void fullFlow_linkRequiredThenPasswordLoginThenLink_thenGoogleSignInWorks() throws Exception {
        RegisteredUser existing = registerUser(uniqueEmail());
        GoogleTestTokens.IdToken token = googleTokens.idToken().email(existing.email());
        String linkToken = requestLinkToken(token);
        String accessToken = loginWithPassword(existing.email());

        postLinkGoogle(accessToken, linkTokenJson(linkToken)).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(jsonPath("$.id").value(existing.id().toString()))
                .andExpect(jsonPath("$.hasPassword").value(true))
                .andExpect(jsonPath("$.linkedProviders", contains("GOOGLE")));

        postGoogleSignIn(token.sign())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(existing.id().toString()));
        assertThat(countIdentities(token.getSubject())).isEqualTo(1);
    }

    @Test
    void linkTokenIssuedForAnotherEmail_returns400() throws Exception {
        RegisteredUser caller = registerUser(uniqueEmail());
        RegisteredUser victim = registerUser(uniqueEmail());
        GoogleTestTokens.IdToken token = googleTokens.idToken().email(victim.email());
        String linkToken = requestLinkToken(token);

        postLinkGoogle(caller.accessToken(), linkTokenJson(linkToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Mã liên kết này dành cho tài khoản khác"));

        assertThat(countIdentities(token.getSubject())).isZero();
    }

    @Test
    void expiredLinkToken_returns400() throws Exception {
        RegisteredUser existing = registerUser(uniqueEmail());
        String linkToken = requestLinkToken(googleTokens.idToken().email(existing.email()));
        String accessToken = loginWithPassword(existing.email());

        clock.advance(Duration.ofMinutes(12)); // linkToken 10 phút (+60 giây dung sai) đã hết; access token 15 phút còn

        postLinkGoogle(accessToken, linkTokenJson(linkToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Mã xác thực Google không hợp lệ hoặc đã hết hạn"));
    }

    @Test
    void forgedLinkTokenOrGoogleIdTokenInLinkTokenField_returns400() throws Exception {
        RegisteredUser existing = registerUser(uniqueEmail());
        String genuine = googleLinkTokens.issue(
                new GoogleIdentity(GoogleTestTokens.uniqueSubject(), existing.email(), true, null));
        String other = googleLinkTokens.issue(
                new GoogleIdentity(GoogleTestTokens.uniqueSubject(), existing.email(), true, null));
        String forged = genuine.substring(0, genuine.lastIndexOf('.')) + other.substring(other.lastIndexOf('.'));

        postLinkGoogle(existing.accessToken(), linkTokenJson(forged)).andExpect(status().isBadRequest());
        postLinkGoogle(existing.accessToken(), linkTokenJson(googleTokens.idToken().email(existing.email()).sign()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void linkTokenCannotBeUsedAsAccessToken() throws Exception {
        RegisteredUser existing = registerUser(uniqueEmail());
        String linkToken = requestLinkToken(googleTokens.idToken().email(existing.email()));

        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(linkToken)))
                .andExpect(status().isUnauthorized());
    }

    // Spec 3.1 (12.1): access token (typ access) dùng làm linkToken → 400 ở me/identities/google — không 401, vì 401
    // làm app refresh rồi đăng xuất. Không liên kết gì.
    @Test
    void accessTokenUsedAsLinkToken_returns400_andLinksNothing() throws Exception {
        RegisteredUser existing = registerUser(uniqueEmail());

        postLinkGoogle(existing.accessToken(), linkTokenJson(existing.accessToken()))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(existing.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linkedProviders").isEmpty());
    }
}
