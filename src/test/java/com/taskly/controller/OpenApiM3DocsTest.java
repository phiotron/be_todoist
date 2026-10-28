package com.taskly.controller;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/** Hợp đồng OpenAPI của các endpoint M3 (nguồn sinh types.gen.ts của mobile). */
class OpenApiM3DocsTest extends IntegrationTestBase {

    @Test
    void apiDocs_listsAllM3Endpoints() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/me'].patch").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/password'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/settings'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/settings'].patch").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/settings/reset'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/password-reset/request'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/password-reset/confirm'].post").exists());
    }

    @Test
    void apiDocs_accountEndpointsNeedBearer_passwordResetDoesNot() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/v1/me'].patch.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/password'].post.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/settings'].get.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/settings'].patch.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/settings/reset'].post.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/password-reset/request'].post.security").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/auth/password-reset/confirm'].post.security").doesNotExist());
    }

    @Test
    void apiDocs_passwordResetResponseCodes() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/v1/auth/password-reset/request'].post.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/password-reset/confirm'].post.responses['204']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/password'].post.responses['200']").exists());
    }

    @Test
    void apiDocs_m3SchemasMatchContract() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.components.schemas.SettingsResponse.required")
                        .value(containsInAnyOrder("theme", "appearance", "locale", "timeZone", "startDay", "nextWeek",
                                "weekendStartDay", "dateFormat", "timeFormat", "startPage", "smartDateRecognition",
                                "swipeLeft", "swipeRight", "navTabs", "remindersEnabled", "autoReminderMinutes",
                                // M3x
                                "addButtonPlacement", "syncHomeView", "navMenuShowTaskCount", "navMenuItems",
                                // M6b
                                "quickAddChips", "quickAddShowActionLabels", "quickAddShowDescription",
                                "resetSubtasks"))) // M6c
                .andExpect(jsonPath("$.components.schemas.SettingsResponse.properties.addButtonPlacement.enum")
                        .value(containsInAnyOrder("LEFT", "CENTER", "RIGHT")))
                .andExpect(jsonPath("$.components.schemas.SettingsResponse.properties.theme.enum")
                        .value(containsInAnyOrder("TASKLY", "MOONSTONE", "TANGERINE", "KALE", "BLUEBERRY",
                                "LAVENDER", "RASPBERRY")))
                .andExpect(jsonPath("$.components.schemas.SettingsResponse.properties.appearance.enum")
                        .value(containsInAnyOrder("AUTO", "LIGHT", "DARK")))
                .andExpect(jsonPath("$.components.schemas.SettingsResponse.properties.swipeLeft.enum")
                        .value(containsInAnyOrder("COMPLETE", "SCHEDULE", "DELETE", "REMINDERS", "SELECT", "NONE")))
                .andExpect(jsonPath("$.components.schemas.SettingsResponse.properties.locale.enum")
                        .value(containsInAnyOrder("vi", "en")))
                .andExpect(jsonPath("$.components.schemas.UpdateSettingsRequest.properties.theme.enum")
                        .value(containsInAnyOrder("TASKLY", "MOONSTONE", "TANGERINE", "KALE", "BLUEBERRY",
                                "LAVENDER", "RASPBERRY")))
                .andExpect(jsonPath("$.components.schemas.UpdateSettingsRequest.properties.appearance.enum")
                        .value(containsInAnyOrder("AUTO", "LIGHT", "DARK")))
                .andExpect(jsonPath("$.components.schemas.UpdateSettingsRequest.required").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.UpdateProfileRequest.required")
                        .value(containsInAnyOrder("displayName")))
                .andExpect(jsonPath("$.components.schemas.ChangePasswordRequest.required")
                        .value(containsInAnyOrder("currentPassword", "newPassword")))
                .andExpect(jsonPath("$.components.schemas.ForgotPasswordRequest.required")
                        .value(containsInAnyOrder("email")))
                .andExpect(jsonPath("$.components.schemas.ResetPasswordRequest.required")
                        .value(containsInAnyOrder("token", "newPassword")));
    }
}
