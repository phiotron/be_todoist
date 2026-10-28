package com.taskly;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.support.AppleTestTokens;
import com.taskly.support.FakeFacebookClient;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Nền cho test Apple / Facebook (M13b). KHÔNG thêm annotation: dùng chung context với IntegrationTestBase
 * (M13bTestConfiguration đã @Import ở lớp gốc). Token Apple ký bằng khoá giả; Facebook là FakeFacebookClient.
 */
public abstract class SocialIntegrationTestBase extends GoogleIntegrationTestBase {

    protected static final String APPLE_SIGN_IN = "/api/v1/auth/apple";
    protected static final String FACEBOOK_SIGN_IN = "/api/v1/auth/facebook";
    protected static final String APPLE_IDENTITY = "/api/v1/me/identities/apple";
    protected static final String FACEBOOK_IDENTITY = "/api/v1/me/identities/facebook";

    @Autowired
    protected AppleTestTokens appleTokens;

    @Autowired
    protected FakeFacebookClient facebook;

    protected record SocialUser(UUID id, String email, String accessToken) {
    }

    protected ResultActions postAppleSignIn(String idToken, String displayName) throws Exception {
        String body = displayName == null
                ? "{\"idToken\": \"%s\"}".formatted(idToken)
                : "{\"idToken\": \"%s\", \"displayName\": \"%s\"}".formatted(idToken, displayName);
        return mockMvc.perform(post(APPLE_SIGN_IN).with(randomRemoteAddress())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected ResultActions postFacebookSignIn(String accessToken) throws Exception {
        return mockMvc.perform(post(FACEBOOK_SIGN_IN).with(randomRemoteAddress())
                .contentType(MediaType.APPLICATION_JSON).content("{\"accessToken\": \"%s\"}".formatted(accessToken)));
    }

    protected SocialUser signInWithApple(AppleTestTokens.IdToken token) throws Exception {
        String json = body(postAppleSignIn(token.sign(), null).andExpect(status().isOk()).andReturn());
        return new SocialUser(UUID.fromString(JsonPath.read(json, "$.user.id")), JsonPath.read(json, "$.user.email"),
                JsonPath.read(json, "$.accessToken"));
    }

    protected SocialUser signInWithFacebook(String accessToken) throws Exception {
        String json = body(postFacebookSignIn(accessToken).andExpect(status().isOk()).andReturn());
        return new SocialUser(UUID.fromString(JsonPath.read(json, "$.user.id")), JsonPath.read(json, "$.user.email"),
                JsonPath.read(json, "$.accessToken"));
    }

    protected ResultActions postLink(String path, String accessToken, String json) throws Exception {
        return mockMvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected ResultActions deleteLink(String path, String accessToken) throws Exception {
        return mockMvc.perform(delete(path).header(HttpHeaders.AUTHORIZATION, bearer(accessToken)));
    }

    protected static String uniqueFacebookId() {
        return "fb-" + UUID.randomUUID();
    }

    protected int countIdentities(String provider, String subject) {
        return jdbcTemplate.queryForObject("select count(*) from user_identities where provider = ? and subject = ?",
                Integer.class, provider, subject);
    }
}
