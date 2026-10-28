package com.taskly.dto.notification;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.taskly.dto.comment.CommentAuthor;
import com.taskly.entity.NotificationType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** Một mục trong hộp thông báo (M8b). actor null = người làm đã xoá tài khoản. */
public record NotificationResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) NotificationType type,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) CommentAuthor actor,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) UUID taskId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String taskContent,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID projectId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String projectName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) UUID commentId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                description = "≤ 200 ký tự đầu của comment") String commentExcerpt,
        @JsonProperty("isRead") @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isRead,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt) {
}
