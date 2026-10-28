package com.taskly.controller;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** Hợp đồng OpenAPI của M12 (địa điểm, nhắc theo vị trí) — mobile sinh types.gen.ts từ đây. */
class OpenApiM12DocsTest extends IntegrationTestBase {

    private ResultActions docs() throws Exception {
        return mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    @Test
    void locationEndpointsAreDocumentedWithBearerAuth() throws Exception {
        docs()
                .andExpect(jsonPath("$.paths['/api/v1/locations'].get.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/locations'].post.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/locations/{locationId}'].delete.security[0].bearerAuth")
                        .exists())
                .andExpect(jsonPath("$.paths['/api/v1/tasks/location-reminders'].get.security[0].bearerAuth")
                        .exists());
    }

    @Test
    void m12SchemasMarkEveryFieldRequired() throws Exception {
        docs()
                .andExpect(jsonPath("$.components.schemas.LocationResponse.required").value(containsInAnyOrder(
                        "id", "name", "latitude", "longitude", "radius", "createdAt")))
                .andExpect(jsonPath("$.components.schemas.CreateLocationRequest.required").value(containsInAnyOrder(
                        "name", "latitude", "longitude", "radius")))
                .andExpect(jsonPath("$.components.schemas.LocationReminderResponse.required").value(
                        containsInAnyOrder("reminderId", "taskId", "content", "locationId", "name", "latitude",
                                "longitude", "radius", "trigger")))
                .andExpect(jsonPath("$.components.schemas.LocationReminderResponse.properties.trigger.enum")
                        .value(containsInAnyOrder("ON_ENTER", "ON_LEAVE")))
                .andExpect(jsonPath("$.components.schemas.CreateReminderRequest.properties.trigger.enum")
                        .value(containsInAnyOrder("ON_ENTER", "ON_LEAVE")));
    }
}
