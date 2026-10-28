package com.taskly.controller;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** Hợp đồng OpenAPI của M13c: endpoint, bảo mật, schema (mobile khai báo kiểu tay theo đúng tên này). */
class OpenApiM13cDocsTest extends IntegrationTestBase {

    private ResultActions docs() throws Exception {
        return mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    @Test
    void endpointsAreDocumented() throws Exception {
        docs()
                .andExpect(jsonPath("$.paths['/api/v1/developer/apps'].post.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/developer/apps/{appId}/webhook'].put").exists())
                .andExpect(jsonPath("$.paths['/api/v1/developer/apps/{appId}/webhook/secret'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/oauth/authorize'].post.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/oauth/authorize/preview'].get.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/oauth/token'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/oauth/token'].post.security").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/oauth/revoke'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/authorized-apps'].get.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/authorized-apps/{clientId}'].delete").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/push-tokens'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/push-tokens/unregister'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/push-tokens/test'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/notification-prefs'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/notification-prefs'].patch").exists());
    }

    @Test
    void responseSchemasMarkTheirFieldsRequired() throws Exception {
        docs()
                .andExpect(jsonPath("$.components.schemas.NotificationPrefsResponse.required").value(containsInAnyOrder(
                        "reminderPush", "reminderEmail", "morningOverview", "eveningReview", "subscribedEmails")))
                .andExpect(jsonPath("$.components.schemas.AuthorizedAppResponse.required").value(hasItems(
                        "clientId", "name", "scopes", "authorizedAt")))
                .andExpect(jsonPath("$.components.schemas.OAuthTokenResponse.required").value(containsInAnyOrder(
                        "access_token", "token_type", "scope")))
                .andExpect(jsonPath("$.components.schemas.OAuthAuthorizePreviewResponse.required").value(hasItems(
                        "clientId", "appName", "redirectUri", "scopes")))
                .andExpect(jsonPath("$.components.schemas.PushTestResponse.required").value(containsInAnyOrder("devices")))
                .andExpect(jsonPath("$.components.schemas.DeveloperAppResponse.required").value(hasItems(
                        "id", "name", "clientId", "redirectUri", "createdAt")));
    }
}
