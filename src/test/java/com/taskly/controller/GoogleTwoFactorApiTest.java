package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.GoogleIntegrationTestBase;
import com.taskly.support.GoogleTestTokens;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** Spec bản 3 (12.1, 12.10, 13): đăng nhập Google vào tài khoản bật 2FA vẫn bị đòi mã, như auth/login. */
class GoogleTwoFactorApiTest extends GoogleIntegrationTestBase {

    @Test
    void googleOnlyAccountWithTwoFactor_signInReturns401ChallengeAndIssuesNothing() throws Exception {
        GoogleTestTokens.IdToken first = googleTokens.idToken();
        GoogleUser user = signInWithGoogle(first);
        enableTwoFactor(user.accessToken());
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(jsonPath("$.twoFactorEnabled").value(true))
                .andExpect(jsonPath("$.hasPassword").value(false));
        int refreshTokensBefore = countRefreshTokens(user.id());

        postGoogleSignIn(googleTokens.idToken().subject(first.getSubject()).sign())
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("two-factor-required"))
                .andExpect(jsonPath("$.twoFactorToken").isString())
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist());

        assertThat(countRefreshTokens(user.id())).as("401 không phát refresh token").isEqualTo(refreshTokensBefore);
        assertThat(countIdentities(first.getSubject())).isEqualTo(1);
    }

    @Test
    void twoFactorTokenFromGoogle_verifiedWithTotp_issuesTokensForSameAccount() throws Exception {
        GoogleTestTokens.IdToken first = googleTokens.idToken();
        GoogleUser user = signInWithGoogle(first);
        TwoFactorSetup twoFactor = enableTwoFactor(user.accessToken());
        String twoFactorToken = requestTwoFactorTokenWithGoogle(googleTokens.idToken().subject(first.getSubject()));

        String json = body(postTwoFactorVerify(twoFactorToken, currentTotp(twoFactor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(user.id().toString()))
                .andExpect(jsonPath("$.user.linkedProviders", contains("GOOGLE")))
                .andReturn());

        mockMvc.perform(get("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(JsonPath.read(json, "$.accessToken"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.id().toString()));
    }

    @Test
    void twoFactorTokenFromGoogle_acceptsRecoveryCode() throws Exception {
        GoogleTestTokens.IdToken first = googleTokens.idToken();
        GoogleUser user = signInWithGoogle(first);
        TwoFactorSetup twoFactor = enableTwoFactor(user.accessToken());
        String twoFactorToken = requestTwoFactorTokenWithGoogle(googleTokens.idToken().subject(first.getSubject()));

        postTwoFactorVerify(twoFactorToken, twoFactor.recoveryCodes().get(0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(user.id().toString()));
    }

    @Test
    void passwordAccountWithGoogleLinkedAndTwoFactor_googleSignInIsChallengedToo() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        GoogleTestTokens.IdToken token = googleTokens.idToken();
        postLinkGoogle(user.accessToken(), idTokenJson(token.sign())).andExpect(status().isNoContent());
        enableTwoFactor(user.accessToken());

        postGoogleSignIn(googleTokens.idToken().subject(token.getSubject()).sign())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("two-factor-required"))
                .andExpect(jsonPath("$.twoFactorToken").isString());
    }

    // Tình huống 2 trên tài khoản bật 2FA: 409 link-required (chưa liên kết → chưa tới bước 2FA, không phát
    // twoFactorToken); đăng nhập mật khẩu phải qua 2FA rồi mới liên kết được; sau đó Google cũng bị đòi mã.
    @Test
    void linkRequiredFlowOnTwoFactorAccount_requiresCodeBeforeLinking_thenGoogleIsChallenged() throws Exception {
        RegisteredUser existing = registerUser(uniqueEmail());
        TwoFactorSetup twoFactor = enableTwoFactor(existing.accessToken());
        GoogleTestTokens.IdToken token = googleTokens.idToken().email(existing.email());

        String linkToken = JsonPath.read(body(postGoogleSignIn(token.sign())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("link-required"))
                .andExpect(jsonPath("$.twoFactorToken").doesNotExist())
                .andReturn()), "$.linkToken");

        String passwordChallenge = JsonPath.read(body(mockMvc.perform(post("/api/v1/auth/login")
                        .with(randomRemoteAddress())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(existing.email(), FIXTURE_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("two-factor-required"))
                .andReturn()), "$.twoFactorToken");
        String accessToken = JsonPath.read(body(postTwoFactorVerify(passwordChallenge, currentTotp(twoFactor))
                .andExpect(status().isOk())
                .andReturn()), "$.accessToken");

        postLinkGoogle(accessToken, linkTokenJson(linkToken)).andExpect(status().isNoContent());

        postGoogleSignIn(token.sign())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("two-factor-required"));
        assertThat(countIdentities(token.getSubject())).isEqualTo(1);
    }

    // Spec 3.1 (12.1): linkToken (typ google-link, aud taskly-google-link) và twoFactorToken (typ two-factor, aud taskly-2fa)
    // cùng ký bằng JWT_SECRET → không dùng chéo được (link làm twoFactorToken → 401; twoFactor làm linkToken → 400).
    @Test
    void linkTokenAndTwoFactorTokenAreNotInterchangeable() throws Exception {
        RegisteredUser existing = registerUser(uniqueEmail());
        TwoFactorSetup twoFactor = enableTwoFactor(existing.accessToken());
        String linkToken = JsonPath.read(body(postGoogleSignIn(googleTokens.idToken().email(existing.email()).sign())
                .andExpect(status().isConflict())
                .andReturn()), "$.linkToken");

        postTwoFactorVerify(linkToken, currentTotp(twoFactor))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.accessToken").doesNotExist());

        GoogleTestTokens.IdToken token = googleTokens.idToken();
        GoogleUser googleUser = signInWithGoogle(token);
        enableTwoFactor(googleUser.accessToken());
        String twoFactorToken = requestTwoFactorTokenWithGoogle(googleTokens.idToken().subject(token.getSubject()));

        postLinkGoogle(existing.accessToken(), linkTokenJson(twoFactorToken))
                .andExpect(status().isBadRequest());
        assertThat(countIdentities(token.getSubject())).isEqualTo(1);
    }

    // Spec 5.9 / 18.1 m10: twoFactorToken dùng một lần (jti) — kể cả token phát từ auth/google. Dời đồng hồ 30 giây
    // trước lần dùng thứ hai để lý do từ chối là "token đã dùng", không phải "bước TOTP đã dùng".
    @Test
    void twoFactorTokenFromGoogle_isSingleUse() throws Exception {
        GoogleTestTokens.IdToken first = googleTokens.idToken();
        GoogleUser user = signInWithGoogle(first);
        TwoFactorSetup twoFactor = enableTwoFactor(user.accessToken());
        String twoFactorToken = requestTwoFactorTokenWithGoogle(googleTokens.idToken().subject(first.getSubject()));

        postTwoFactorVerify(twoFactorToken, currentTotp(twoFactor)).andExpect(status().isOk());

        clock.advance(Duration.ofSeconds(30));
        postTwoFactorVerify(twoFactorToken, currentTotp(twoFactor))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void neverLogsTwoFactorTokenFromGoogle(CapturedOutput output) throws Exception {
        GoogleTestTokens.IdToken first = googleTokens.idToken();
        GoogleUser user = signInWithGoogle(first);
        enableTwoFactor(user.accessToken());

        String twoFactorToken = requestTwoFactorTokenWithGoogle(googleTokens.idToken().subject(first.getSubject()));

        assertThat(output.getAll()).doesNotContain(twoFactorToken);
    }
}
