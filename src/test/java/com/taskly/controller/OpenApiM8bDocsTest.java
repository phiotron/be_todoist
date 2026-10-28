package com.taskly.controller;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** Hợp đồng OpenAPI của M8b: comment, file, thông báo, ảnh đại diện — mọi field response bắt buộc. */
class OpenApiM8bDocsTest extends IntegrationTestBase {

    private ResultActions docs() throws Exception {
        return mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    @Test
    void endpointsAreDocumented() throws Exception {
        docs()
                .andExpect(jsonPath("$.paths['/api/v1/tasks/{taskId}/comments'].get.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/tasks/{taskId}/comments'].post.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/projects/{projectId}/comments'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/comments/{commentId}'].delete").exists())
                .andExpect(jsonPath("$.paths['/api/v1/comments/{commentId}/reactions'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/comments/{commentId}/reactions'].delete").exists())
                .andExpect(jsonPath("$.paths['/api/v1/attachments'].post.requestBody.content['multipart/form-data']")
                        .exists())
                .andExpect(jsonPath("$.paths['/api/v1/attachments/{attachmentId}/link'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/attachments/{attachmentId}/download'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/notifications'].get.parameters[?(@.name == 'unread')]").exists())
                .andExpect(jsonPath("$.paths['/api/v1/notifications/unread-count'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/notifications/read-all'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/notifications/{notificationId}/read'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/notification-settings'].put").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/avatar'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/avatar'].delete").exists())
                .andExpect(jsonPath("$.paths['/api/v1/users/{userId}/avatar'].get").exists());
    }

    @Test
    void responseSchemasMarkEveryFieldRequired() throws Exception {
        docs()
                .andExpect(jsonPath("$.components.schemas.CommentResponse.required").value(containsInAnyOrder(
                        "id", "taskId", "projectId", "author", "content", "attachment", "reactions", "mentionUserIds",
                        "isMine", "createdAt")))
                .andExpect(jsonPath("$.components.schemas.CommentAuthor.required")
                        .value(containsInAnyOrder("id", "name", "avatarUrl")))
                .andExpect(jsonPath("$.components.schemas.AttachmentResponse.required")
                        .value(containsInAnyOrder("id", "fileName", "contentType", "sizeBytes", "isImage")))
                .andExpect(jsonPath("$.components.schemas.ReactionSummary.required")
                        .value(containsInAnyOrder("emoji", "count", "reactedByMe", "userIds")))
                .andExpect(jsonPath("$.components.schemas.NotificationResponse.required").value(containsInAnyOrder(
                        "id", "type", "actor", "taskId", "taskContent", "projectId", "projectName", "commentId",
                        "commentExcerpt", "isRead", "createdAt")))
                .andExpect(jsonPath("$.components.schemas.UnreadCountResponse.required")
                        .value(containsInAnyOrder("count")))
                .andExpect(jsonPath("$.components.schemas.AttachmentLinkResponse.required")
                        .value(containsInAnyOrder("url", "expiresAt")))
                .andExpect(jsonPath("$.components.schemas.CommentSearchItem.required").value(containsInAnyOrder(
                        "id", "taskId", "projectId", "projectName", "taskContent", "content")))
                .andExpect(jsonPath("$.components.schemas.NotificationSettingItem.required")
                        .value(containsInAnyOrder("scope", "event", "enabled")))
                .andExpect(jsonPath("$.components.schemas.CreateCommentRequest.required")
                        .value(containsInAnyOrder("content")));
    }

    @Test
    void enumsAreDocumented() throws Exception {
        docs()
                .andExpect(jsonPath("$.components.schemas.NotificationResponse.properties.type.enum")
                        .value(containsInAnyOrder("COMMENT_MENTION")))
                .andExpect(jsonPath("$.components.schemas.NotificationSettingItem.properties.scope.enum")
                        .value(containsInAnyOrder("PROJECT", "WORKSPACE")))
                .andExpect(jsonPath("$.components.schemas.NotificationSettingItem.properties.event.enum")
                        .value(containsInAnyOrder("COMMENT_MENTION", "COMMENT_ADDED", "TASK_ASSIGNED", "TASK_COMPLETED",
                                "MEMBER_JOINED", "INVITATION_ACCEPTED")));
    }
}
