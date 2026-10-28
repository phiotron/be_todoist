package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.SocialIntegrationTestBase;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** Callback luồng web Apple (form_post) / Facebook (GET code) → 303 về deep link của app (M13b). */
class SocialCallbackApiTest extends SocialIntegrationTestBase {

    private static Map<String, String> query(String location) {
        URI uri = URI.create(location);
        Map<String, String> params = new LinkedHashMap<>();
        if (uri.getRawQuery() != null) {
            for (String pair : uri.getRawQuery().split("&")) {
                String[] kv = pair.split("=", 2);
                params.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return params;
    }

    @Test
    void appleFormPostRedirectsToTheAppWithTokenStateAndName() throws Exception {
        String idToken = appleTokens.idToken().sign();

        String location = mockMvc.perform(post("/api/v1/auth/apple/callback")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer expired-or-junk")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("state", "st-123")
                        .param("code", "c")
                        .param("id_token", idToken)
                        .param("user", "{\"name\":{\"firstName\":\"Lan\",\"lastName\":\"Nguyễn\"},\"email\":\"x@y\"}"))
                .andExpect(status().isSeeOther())
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);

        assertThat(location).startsWith("taskly://oauth/apple?");
        assertThat(location).doesNotContain("+");
        assertThat(query(location)).containsEntry("state", "st-123").containsEntry("idToken", idToken)
                .containsEntry("name", "Lan Nguyễn");
    }

    @Test
    void appleCancelAndFailureBecomeErrorCodes() throws Exception {
        String cancelled = mockMvc.perform(post("/api/v1/auth/apple/callback")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("state", "s").param("error", "user_cancelled_authorize"))
                .andExpect(status().isSeeOther()).andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(query(cancelled)).containsEntry("error", "cancelled").doesNotContainKey("idToken");

        String failed = mockMvc.perform(post("/api/v1/auth/apple/callback")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("state", "s"))
                .andExpect(status().isSeeOther()).andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(query(failed)).containsEntry("error", "failed");
    }

    @Test
    void facebookCodeIsExchangedForAnAccessToken() throws Exception {
        String accessToken = facebook.register(uniqueFacebookId(), null, "A");
        String code = facebook.registerCode(accessToken);

        String location = mockMvc.perform(get("/api/v1/auth/facebook/callback").with(randomRemoteAddress())
                        .param("state", "fb-state").param("code", code))
                .andExpect(status().isSeeOther()).andReturn().getResponse().getHeader(HttpHeaders.LOCATION);

        assertThat(location).startsWith("taskly://oauth/facebook?");
        assertThat(query(location)).containsEntry("state", "fb-state").containsEntry("accessToken", accessToken);
    }

    @Test
    void facebookDeniedOrBadCodeRedirectsWithAnError() throws Exception {
        String denied = mockMvc.perform(get("/api/v1/auth/facebook/callback")
                        .param("state", "s").param("error", "access_denied"))
                .andExpect(status().isSeeOther()).andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(query(denied)).containsEntry("error", "cancelled");

        String bad = mockMvc.perform(get("/api/v1/auth/facebook/callback").with(randomRemoteAddress())
                        .param("state", "s").param("code", "unknown"))
                .andExpect(status().isSeeOther()).andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(query(bad)).containsEntry("error", "failed").doesNotContainKey("accessToken");
    }

    @Test
    void appleNameParsingIgnoresJunk() {
        assertThat(SocialAuthController.appleName(null)).isNull();
        assertThat(SocialAuthController.appleName("not json")).isNull();
        assertThat(SocialAuthController.appleName("{\"name\":{\"firstName\":\"An\"}}")).isEqualTo("An");
        assertThat(SocialAuthController.appleName("{\"email\":\"a@b\"}")).isNull();
    }
}
