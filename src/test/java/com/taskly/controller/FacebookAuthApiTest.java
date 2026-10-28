package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.SocialIntegrationTestBase;
import com.taskly.support.FakeFacebookClient;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** POST auth/facebook + me/identities/facebook (M13b). Facebook là FakeFacebookClient (không gọi Graph API). */
class FacebookAuthApiTest extends SocialIntegrationTestBase {

    private static String accessJson(String token) {
        return "{\"accessToken\": \"%s\"}".formatted(token);
    }

    @Test
    void firstSignInCreatesAnAccountNamedAfterTheFacebookProfile() throws Exception {
        String subject = uniqueFacebookId();
        String email = "fb-" + UUID.randomUUID() + "@example.com";
        String token = facebook.register(subject, email, "Minh Trần");

        postFacebookSignIn(token).andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.displayName").value("Minh Trần"))
                .andExpect(jsonPath("$.user.linkedProviders[0]").value("FACEBOOK"));

        assertThat(countIdentities("FACEBOOK", subject)).isEqualTo(1);
    }

    @Test
    void accountWithoutEmailCannotSignUpButCanLinkFromSettingsAndThenSignIn() throws Exception {
        String subject = uniqueFacebookId();
        String token = facebook.register(subject, null, "Không Email");

        postFacebookSignIn(token).andExpect(status().isForbidden());

        RegisteredUser user = registerUser(uniqueEmail());
        postLink(FACEBOOK_IDENTITY, user.accessToken(), accessJson(token)).andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject("select email from user_identities where provider = 'FACEBOOK' "
                + "and subject = ?", String.class, subject)).isNull();
        String json = body(postFacebookSignIn(token).andExpect(status().isOk()).andReturn());
        assertThat((String) JsonPath.read(json, "$.user.id")).isEqualTo(user.id().toString());
    }

    @Test
    void invalidTokenIs401AndUnavailableGraphIs503() throws Exception {
        postFacebookSignIn("not-a-token").andExpect(status().isUnauthorized());
        postFacebookSignIn(FakeFacebookClient.UNAVAILABLE_TOKEN).andExpect(status().isServiceUnavailable());
    }

    @Test
    void emailOfAPasswordAccountNeedsLinking() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String token = facebook.register(uniqueFacebookId(), user.email(), "Trùng Email");

        String linkToken = JsonPath.read(body(postFacebookSignIn(token).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("link-required")).andReturn()), "$.linkToken");

        postLink(FACEBOOK_IDENTITY, loginWithPassword(user.email()), linkTokenJson(linkToken))
                .andExpect(status().isNoContent());
        authGet(user.accessToken(), "/api/v1/me").andExpect(jsonPath("$.linkedProviders", hasItem("FACEBOOK")));
    }

    @Test
    void linkErrorsAre400AndUnlinkRules() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        postLink(FACEBOOK_IDENTITY, user.accessToken(), accessJson("bad")).andExpect(status().isBadRequest());
        postLink(FACEBOOK_IDENTITY, user.accessToken(), "{}").andExpect(status().isBadRequest());

        String token = facebook.register(uniqueFacebookId(), "fb-" + UUID.randomUUID() + "@example.com", "A");
        postLink(FACEBOOK_IDENTITY, user.accessToken(), accessJson(token)).andExpect(status().isNoContent());
        RegisteredUser other = registerUser(uniqueEmail());
        postLink(FACEBOOK_IDENTITY, other.accessToken(), accessJson(token)).andExpect(status().isConflict());

        deleteLink(FACEBOOK_IDENTITY, user.accessToken()).andExpect(status().isNoContent());
        deleteLink(FACEBOOK_IDENTITY, user.accessToken()).andExpect(status().isNotFound());
    }

    @Test
    void sameEmailCanCarryGoogleAppleAndFacebookTogether() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        postLinkGoogle(user.accessToken(), idTokenJson(googleTokens.idToken().sign())).andExpect(status().isNoContent());
        postLink(APPLE_IDENTITY, user.accessToken(), idTokenJson(appleTokens.idToken().sign()))
                .andExpect(status().isNoContent());
        postLink(FACEBOOK_IDENTITY, user.accessToken(), accessJson(facebook.register(uniqueFacebookId(),
                "x-" + UUID.randomUUID() + "@example.com", "X"))).andExpect(status().isNoContent());

        authGet(user.accessToken(), "/api/v1/me")
                .andExpect(jsonPath("$.linkedProviders[0]").value("GOOGLE"))
                .andExpect(jsonPath("$.linkedProviders[1]").value("APPLE"))
                .andExpect(jsonPath("$.linkedProviders[2]").value("FACEBOOK"));
    }
}
