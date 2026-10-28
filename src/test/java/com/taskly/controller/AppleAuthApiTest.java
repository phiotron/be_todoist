package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.SocialIntegrationTestBase;
import com.taskly.support.AppleTestTokens;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** POST auth/apple + me/identities/apple (M13b) — đúng bảng liên kết Google của spec 12.1. */
class AppleAuthApiTest extends SocialIntegrationTestBase {

    @Test
    void firstSignInCreatesPasswordlessAccountWithTheRelayEmail() throws Exception {
        AppleTestTokens.IdToken token = appleTokens.idToken();

        String json = body(postAppleSignIn(token.sign(), "Lan Nguyễn").andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value(token.getEmail()))
                .andExpect(jsonPath("$.user.displayName").value("Lan Nguyễn"))
                .andExpect(jsonPath("$.user.hasPassword").value(false))
                .andExpect(jsonPath("$.user.linkedProviders[0]").value("APPLE"))
                .andReturn());

        assertThat(countIdentities("APPLE", token.getSubject())).isEqualTo(1);
        // Đăng nhập lần sau bằng cùng sub → cùng tài khoản, không tạo thêm.
        String again = body(postAppleSignIn(token.sign(), null).andExpect(status().isOk()).andReturn());
        assertThat((String) JsonPath.read(again, "$.user.id")).isEqualTo(JsonPath.read(json, "$.user.id"));
        assertThat(countUsersByEmail(token.getEmail())).isEqualTo(1);
    }

    @Test
    void withoutANameTheEmailPrefixBecomesTheDisplayName() throws Exception {
        AppleTestTokens.IdToken token = appleTokens.idToken().email("an.apple@privaterelay.appleid.com"
                .replace("an.apple", "an-" + System.nanoTime()));

        postAppleSignIn(token.sign(), "   ").andExpect(status().isOk())
                .andExpect(jsonPath("$.user.displayName").value(token.getEmail().substring(0,
                        token.getEmail().indexOf('@'))));
    }

    @Test
    void rejectsTokensThatFailVerificationWith401() throws Exception {
        postAppleSignIn(appleTokens.idToken().signedByUntrustedKey().sign(), null).andExpect(status().isUnauthorized());
        postAppleSignIn(appleTokens.idToken().audience("someone-else").sign(), null)
                .andExpect(status().isUnauthorized());
        postAppleSignIn(appleTokens.idToken().issuer("https://accounts.google.com").sign(), null)
                .andExpect(status().isUnauthorized());
        postAppleSignIn(appleTokens.idToken().lifetime(Duration.ofMinutes(-5)).sign(), null)
                .andExpect(status().isUnauthorized());
        postAppleSignIn(appleTokens.idToken().email(null).sign(), null).andExpect(status().isUnauthorized());
        // Token Google không phải token Apple.
        postAppleSignIn(googleTokens.idToken().sign(), null).andExpect(status().isUnauthorized());
    }

    @Test
    void unverifiedEmailIsForbidden() throws Exception {
        AppleTestTokens.IdToken token = appleTokens.idToken().emailVerified("false");

        postAppleSignIn(token.sign(), null).andExpect(status().isForbidden());

        assertThat(countUsersByEmail(token.getEmail())).isZero();
    }

    @Test
    void existingPasswordAccountGetsLinkRequiredAndTheLinkTokenLinksAfterPasswordSignIn() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        AppleTestTokens.IdToken token = appleTokens.idToken().email(user.email());

        String problem = body(postAppleSignIn(token.sign(), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("link-required"))
                .andExpect(jsonPath("$.linkToken").isNotEmpty())
                .andReturn());
        String linkToken = JsonPath.read(problem, "$.linkToken");
        assertThat(countIdentities("APPLE", token.getSubject())).isZero();

        String access = loginWithPassword(user.email());
        postLink(APPLE_IDENTITY, access, linkTokenJson(linkToken)).andExpect(status().isNoContent());

        authGet(access, "/api/v1/me").andExpect(jsonPath("$.linkedProviders", hasItem("APPLE")));
        String signedIn = body(postAppleSignIn(token.sign(), null).andExpect(status().isOk()).andReturn());
        assertThat((String) JsonPath.read(signedIn, "$.user.id")).isEqualTo(user.id().toString());
    }

    @Test
    void linkTokensAreProviderSpecific() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String appleLinkToken = JsonPath.read(body(postAppleSignIn(appleTokens.idToken().email(user.email()).sign(),
                null).andExpect(status().isConflict()).andReturn()), "$.linkToken");
        String googleLinkToken = JsonPath.read(body(postGoogleSignIn(googleTokens.idToken().email(user.email()).sign())
                .andExpect(status().isConflict()).andReturn()), "$.linkToken");

        postLink(FACEBOOK_IDENTITY, user.accessToken(), linkTokenJson(appleLinkToken)).andExpect(status().isBadRequest());
        postLink(APPLE_IDENTITY, user.accessToken(), linkTokenJson(googleLinkToken)).andExpect(status().isBadRequest());
        postLinkGoogle(user.accessToken(), linkTokenJson(appleLinkToken)).andExpect(status().isBadRequest());
        // linkToken không làm Bearer được.
        authGet(appleLinkToken, "/api/v1/me").andExpect(status().isUnauthorized());
    }

    @Test
    void linkedAccountWithTwoFactorAsksForTheCode() throws Exception {
        AppleTestTokens.IdToken token = appleTokens.idToken();
        SocialUser apple = signInWithApple(token);
        // 2FA bật qua API M3 cho tài khoản (không mật khẩu vẫn bật được ở M3).
        enableTwoFactor(apple.accessToken());

        postAppleSignIn(token.sign(), null).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("two-factor-required"))
                .andExpect(jsonPath("$.twoFactorToken").isNotEmpty());
    }

    @Test
    void linksFromSettingsWithAFreshIdTokenAndTheSameLinkTwiceIsFine() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        AppleTestTokens.IdToken token = appleTokens.idToken();

        postLink(APPLE_IDENTITY, user.accessToken(), idTokenJson(token.sign())).andExpect(status().isNoContent());
        postLink(APPLE_IDENTITY, user.accessToken(), idTokenJson(token.sign())).andExpect(status().isNoContent());

        assertThat(countIdentities("APPLE", token.getSubject())).isEqualTo(1);
        // Apple khác vào tài khoản đã gắn Apple → 409; Apple đã gắn vào tài khoản khác → 409.
        postLink(APPLE_IDENTITY, user.accessToken(), idTokenJson(appleTokens.idToken().sign()))
                .andExpect(status().isConflict());
        RegisteredUser other = registerUser(uniqueEmail());
        postLink(APPLE_IDENTITY, other.accessToken(), idTokenJson(token.sign())).andExpect(status().isConflict());
    }

    @Test
    void linkRequestsWithBadTokensAre400NotUnauthorized() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        postLink(APPLE_IDENTITY, user.accessToken(), idTokenJson(appleTokens.idToken().signedByUntrustedKey().sign()))
                .andExpect(status().isBadRequest());
        postLink(APPLE_IDENTITY, user.accessToken(), "{}").andExpect(status().isBadRequest());
        postLink(APPLE_IDENTITY, user.accessToken(), """
                {"idToken": "%s", "linkToken": "x"}
                """.formatted(appleTokens.idToken().sign())).andExpect(status().isBadRequest());
        postLink(APPLE_IDENTITY, user.accessToken(), linkTokenJson(user.accessToken()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void linkTokenForAnotherEmailIsRejected() throws Exception {
        RegisteredUser owner = registerUser(uniqueEmail());
        RegisteredUser intruder = registerUser(uniqueEmail());
        String linkToken = JsonPath.read(body(postAppleSignIn(appleTokens.idToken().email(owner.email()).sign(), null)
                .andExpect(status().isConflict()).andReturn()), "$.linkToken");

        postLink(APPLE_IDENTITY, intruder.accessToken(), linkTokenJson(linkToken)).andExpect(status().isBadRequest());
    }

    @Test
    void unlinkNeedsALinkAndAPassword() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        deleteLink(APPLE_IDENTITY, user.accessToken()).andExpect(status().isNotFound());
        postLink(APPLE_IDENTITY, user.accessToken(), idTokenJson(appleTokens.idToken().sign()))
                .andExpect(status().isNoContent());

        deleteLink(APPLE_IDENTITY, user.accessToken()).andExpect(status().isNoContent());
        authGet(user.accessToken(), "/api/v1/me").andExpect(jsonPath("$.linkedProviders").isEmpty());

        SocialUser appleOnly = signInWithApple(appleTokens.idToken());
        deleteLink(APPLE_IDENTITY, appleOnly.accessToken()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("password-required"));
    }

    @Test
    void errorsAreTranslated() throws Exception {
        postAppleSignIn(appleTokens.idToken().signedByUntrustedKey().sign(), null)
                .andExpect(jsonPath("$.detail").value("Đăng nhập không thành công, vui lòng thử lại"));
    }
}
