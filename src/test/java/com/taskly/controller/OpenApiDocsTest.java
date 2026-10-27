package com.taskly.controller;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/** /v3/api-docs là nguồn để mobile sinh types.gen.ts (npm run gen:api) — phải công khai và đúng hợp đồng. */
class OpenApiDocsTest extends IntegrationTestBase {

    @Test
    void apiDocs_isPublicAndListsAllM0Endpoints() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/auth/register'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/refresh'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/logout'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me'].get").exists());
    }

    @Test
    void apiDocs_declaresBearerSchemeOnProtectedEndpoints() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/api/v1/me'].get.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/logout'].post.security[0].bearerAuth").exists());
    }

    @Test
    void apiDocs_schemasMatchContractAndHideSecrets() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                // tham số @AuthenticationPrincipal Jwt không được lộ thành query param
                .andExpect(jsonPath("$.paths['/api/v1/me'].get.parameters").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.UserResponse.properties.id").exists())
                .andExpect(jsonPath("$.components.schemas.UserResponse.properties.timeZone").exists())
                .andExpect(jsonPath("$.components.schemas.UserResponse.properties.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.AuthResponse.properties.accessTokenExpiresIn.type")
                        .value("integer"));
    }

    @Test
    void apiDocs_requiredFieldsMatchContractForTypeGeneration() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                // 6 schema DTO tồn tại (openapi-typescript sinh type theo tên)
                .andExpect(jsonPath("$.components.schemas.RegisterRequest").exists())
                .andExpect(jsonPath("$.components.schemas.LoginRequest").exists())
                .andExpect(jsonPath("$.components.schemas.RefreshRequest").exists())
                .andExpect(jsonPath("$.components.schemas.LogoutRequest").exists())
                .andExpect(jsonPath("$.components.schemas.AuthResponse").exists())
                .andExpect(jsonPath("$.components.schemas.UserResponse").exists())
                // response: mọi field bắt buộc, để mobile không phải bọc kiểu optional
                .andExpect(jsonPath("$.components.schemas.AuthResponse.required")
                        .value(containsInAnyOrder("accessToken", "refreshToken", "accessTokenExpiresIn", "user")))
                .andExpect(jsonPath("$.components.schemas.UserResponse.required")
                        .value(containsInAnyOrder("id", "email", "displayName", "locale", "timeZone")))
                // request: field bắt buộc lấy từ Bean Validation
                .andExpect(jsonPath("$.components.schemas.RegisterRequest.required")
                        .value(containsInAnyOrder("email", "password", "displayName")))
                .andExpect(jsonPath("$.components.schemas.LoginRequest.required")
                        .value(containsInAnyOrder("email", "password")))
                .andExpect(jsonPath("$.components.schemas.RefreshRequest.required")
                        .value(containsInAnyOrder("refreshToken")))
                .andExpect(jsonPath("$.components.schemas.LogoutRequest.required")
                        .value(containsInAnyOrder("refreshToken")));
    }

    @Test
    void apiDocs_publicAuthEndpointsDeclareNoSecurity() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/v1/auth/register'].post.security").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.security").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/auth/refresh'].post.security").doesNotExist())
                // không áp security toàn cục: chỉ endpoint cần Bearer mới khai báo
                .andExpect(jsonPath("$.security").doesNotExist());
    }

    @Test
    void swaggerUi_isPublic() throws Exception {
        // Trang Swagger UI trả 200 ổn định trong MockMvc; 401/403 nghĩa là SecurityConfig chặn nhầm.
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }

    @Test
    void apiDocs_listsAllM1Endpoints() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/v1/projects'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/projects'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/projects/{projectId}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/projects/{projectId}'].patch").exists())
                .andExpect(jsonPath("$.paths['/api/v1/projects/{projectId}'].delete").exists())
                .andExpect(jsonPath("$.paths['/api/v1/projects/{projectId}/move'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/projects/{projectId}/archive'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/projects/{projectId}/unarchive'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/projects/{projectId}/favorite'].put").exists())
                .andExpect(jsonPath("$.paths['/api/v1/projects/{projectId}/favorite'].delete").exists())
                .andExpect(jsonPath("$.paths['/api/v1/favorites'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/projects/{projectId}/sections'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/projects/{projectId}/sections'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/projects/{projectId}/sections/{sectionId}'].patch").exists())
                .andExpect(jsonPath("$.paths['/api/v1/projects/{projectId}/sections/{sectionId}'].delete").exists())
                .andExpect(jsonPath("$.paths['/api/v1/projects/{projectId}/sections/{sectionId}/archive'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/projects/{projectId}/sections/{sectionId}/unarchive'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/tasks'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/tasks'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/tasks/{taskId}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/tasks/{taskId}'].patch").exists())
                .andExpect(jsonPath("$.paths['/api/v1/tasks/{taskId}'].delete").exists())
                .andExpect(jsonPath("$.paths['/api/v1/tasks/{taskId}/close'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/tasks/{taskId}/reopen'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/tasks/{taskId}/move'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/tasks/{taskId}/duplicate'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/tasks/{taskId}/subtasks'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/tasks/batch'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/tasks/overdue/reschedule'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/reorder'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/tasks/{taskId}'].get.security[0].bearerAuth").exists());
    }

    @Test
    void apiDocs_noLongerHasTrashOrTaskFavoriteEndpoints() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/v1/trash']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/trash/{batchId}/restore']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/tasks/{taskId}/favorite']").doesNotExist());
    }

    @Test
    void apiDocs_m1ResponsesMarkEveryFieldRequired() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.components.schemas.ProjectResponse.required").value(containsInAnyOrder(
                        "id", "name", "color", "description", "parentId", "isInbox", "isFavorite", "archivedAt",
                        "sortOrder", "viewStyle", "activeTaskCount", "descendantProjectCount",
                        "descendantActiveTaskCount", "createdAt", "updatedAt")))
                .andExpect(jsonPath("$.components.schemas.SectionResponse.required").value(containsInAnyOrder(
                        "id", "projectId", "name", "description", "sortOrder", "archivedAt", "activeTaskCount",
                        "createdAt", "updatedAt")))
                .andExpect(jsonPath("$.components.schemas.TaskResponse.required").value(containsInAnyOrder(
                        "id", "projectId", "sectionId", "parentId", "content", "description", "priority", "dueDate",
                        "dueDatetime", "durationMinutes", "deadlineDate", "dueString", "isOverdue", "completedAt",
                        "subtaskCount", "completedSubtaskCount", "sortOrder", "version", "createdAt", "updatedAt",
                        "projectName", "parentContent", "labelIds")))
                .andExpect(jsonPath("$.components.schemas.TaskResponse.properties.isFavorite").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.TaskChangeResponse.required")
                        .value(containsInAnyOrder("task", "cascaded")))
                .andExpect(jsonPath("$.components.schemas.CascadedTask.required")
                        .value(containsInAnyOrder("id", "version", "completedAt")))
                .andExpect(jsonPath("$.components.schemas.TaskChangeResponse.properties.cascadedTaskIds").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.AffectedTasksResponse.required")
                        .value(containsInAnyOrder("affectedTaskIds")))
                .andExpect(jsonPath("$.components.schemas.FavoriteResponse.required")
                        .value(containsInAnyOrder("targetType", "targetId", "createdAt")))
                .andExpect(jsonPath("$.components.schemas.ReorderResponse.required")
                        .value(containsInAnyOrder("type", "id", "sortOrder")))
                .andExpect(jsonPath("$.components.schemas.PageTaskResponse.required")
                        .value(containsInAnyOrder("items", "nextCursor")))
                .andExpect(jsonPath("$.components.schemas.ProjectResponse.properties.color.enum", hasItem("berry_red")))
                .andExpect(jsonPath("$.components.schemas.ProjectResponse.properties.color.enum", hasItem("taupe")))
                .andExpect(jsonPath("$.components.schemas.ProjectResponse.properties.icon").doesNotExist());
    }

    @Test
    void apiDocs_floatingDueIsAStringWithoutOffset_andBatchActionsAreListed() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.components.schemas.TaskResponse.properties.dueDatetime.type").value(hasItem("string")))
                .andExpect(jsonPath("$.components.schemas.TaskResponse.properties.dueDatetime.format").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.BatchTasksRequest.properties.action.enum", hasItem("DELETE")))
                .andExpect(jsonPath("$.components.schemas.BatchTasksRequest.properties.action.enum", hasItem("PRIORITY")))
                .andExpect(jsonPath("$.components.schemas.BatchTasksRequest.properties.action.enum", hasItem("ADD_LABELS")))
                .andExpect(jsonPath("$.components.schemas.BatchTasksRequest.properties.action.enum", hasItem("REMOVE_LABELS")))
                .andExpect(jsonPath("$.components.schemas.BatchTasksRequest.properties.labelIds").exists());
    }

    @Test
    void apiDocs_createRequestsTakeAnOptionalClientId_reorderNeedsAfterId_andViewHasCompleted() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.components.schemas.CreateTaskRequest.properties.id.format").value("uuid"))
                .andExpect(jsonPath("$.components.schemas.CreateProjectRequest.properties.id.format").value("uuid"))
                .andExpect(jsonPath("$.components.schemas.CreateSectionRequest.properties.id.format").value("uuid"))
                .andExpect(jsonPath("$.components.schemas.ReorderRequest.required", hasItem("afterId")))
                .andExpect(jsonPath("$.paths['/api/v1/tasks'].get.parameters[?(@.name == 'view')].schema.enum[*]",
                        hasItem("completed")))
                .andExpect(jsonPath("$.paths['/api/v1/tasks'].get.parameters[?(@.name == 'since')]").isNotEmpty())
                .andExpect(jsonPath("$.paths['/api/v1/tasks'].get.parameters[?(@.name == 'to')]").isNotEmpty());
    }

    @Test
    void apiDocs_createEndpointsDocumentIdempotencyKeyHeader() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/v1/tasks'].post.parameters[?(@.name == 'Idempotency-Key')].in")
                        .value(hasItem("header")))
                .andExpect(jsonPath("$.paths['/api/v1/projects'].post.parameters[?(@.name == 'Idempotency-Key')]")
                        .isNotEmpty())
                .andExpect(jsonPath("$.paths['/api/v1/projects/{projectId}/sections'].post"
                        + ".parameters[?(@.name == 'Idempotency-Key')]").isNotEmpty())
                .andExpect(jsonPath("$.paths['/api/v1/tasks/{taskId}/duplicate'].post"
                        + ".parameters[?(@.name == 'Idempotency-Key')]").isNotEmpty())
                .andExpect(jsonPath("$.paths['/api/v1/tasks/batch'].post.parameters[?(@.name == 'Idempotency-Key')]")
                        .isNotEmpty());
    }

    @Test
    void apiDocs_statusCodesAndSwaggerConfig() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/v1/auth/register'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/logout'].post.responses['204']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/tasks/{taskId}'].delete.responses['204']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/projects/{projectId}'].delete.responses['204']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/projects/{projectId}/sections/{sectionId}'].delete.responses['204']")
                        .exists())
                .andExpect(jsonPath("$.paths['/api/v1/tasks/{taskId}/duplicate'].post.responses['201']").exists());
        mockMvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk());
    }

    @Test
    void apiDocs_reopenRequestExposesOnlyUndo_notTheIsUndoGetter() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.components.schemas.ReopenTaskRequest.properties.undo").exists())
                .andExpect(jsonPath("$.components.schemas.ReopenTaskRequest.properties.length()").value(1));
    }

    @Test
    void apiDocs_declaresProblemDetailSchemaWithCodeRequestIdAndErrors() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.components.schemas.ProblemDetail.properties.code").exists())
                .andExpect(jsonPath("$.components.schemas.ProblemDetail.properties.requestId").exists())
                .andExpect(jsonPath("$.components.schemas.ProblemDetail.properties.errors.type").value("array"))
                .andExpect(jsonPath("$.components.schemas.ProblemDetail.properties.status").exists())
                .andExpect(jsonPath("$.components.schemas.ProblemDetail.properties.detail").exists());
    }
}
