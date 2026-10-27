package com.taskly.controller;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** Hợp đồng OpenAPI của M4 (phần nhắc việc; schema cài đặt thuộc M3, khoá ở Task 4 sau khi gộp M3). */
class OpenApiM4DocsTest extends IntegrationTestBase {

    private static final String TASK_REMINDERS = "$.paths['/api/v1/tasks/{taskId}/reminders']";
    private static final String ONE_REMINDER = "$.paths['/api/v1/tasks/{taskId}/reminders/{reminderId}']";
    private static final String OCCURRENCES = "$.paths['/api/v1/tasks/reminders']";

    private ResultActions docs() throws Exception {
        return mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    @Test
    void reminderEndpointsAreDocumentedWithBearerAuth() throws Exception {
        docs()
                .andExpect(jsonPath(TASK_REMINDERS + ".get.security[0].bearerAuth").exists())
                .andExpect(jsonPath(TASK_REMINDERS + ".post.security[0].bearerAuth").exists())
                .andExpect(jsonPath(ONE_REMINDER + ".delete.security[0].bearerAuth").exists())
                .andExpect(jsonPath(OCCURRENCES + ".get.security[0].bearerAuth").exists())
                .andExpect(jsonPath(OCCURRENCES + ".get.parameters[?(@.name == 'limit')]").exists());
    }

    @Test
    void reminderResponseSchemasMarkEveryFieldRequired() throws Exception {
        docs()
                .andExpect(jsonPath("$.components.schemas.ReminderResponse.required").value(containsInAnyOrder(
                        "id", "taskId", "type", "at", "minuteOffset", "createdAt")))
                .andExpect(jsonPath("$.components.schemas.ReminderOccurrence.required").value(containsInAnyOrder(
                        "key", "taskId", "reminderId", "kind", "fireAt", "content")))
                .andExpect(jsonPath("$.components.schemas.CreateReminderRequest.required")
                        .value(containsInAnyOrder("type")));
    }

    @Test
    void reminderEnumsAreDocumented() throws Exception {
        docs()
                .andExpect(jsonPath("$.components.schemas.CreateReminderRequest.properties.type.enum")
                        .value(containsInAnyOrder("ABSOLUTE", "RELATIVE")))
                .andExpect(jsonPath("$.components.schemas.ReminderOccurrence.properties.kind.enum")
                        .value(containsInAnyOrder("AUTO", "ABSOLUTE", "RELATIVE")));
    }
}
