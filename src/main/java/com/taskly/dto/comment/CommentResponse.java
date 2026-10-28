package com.taskly.dto.comment;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Comment của task (taskId) hoặc của dự án (projectId) — đúng một trong hai khác null (M8b). author null = tác giả đã
 * xoá tài khoản. Không có updatedAt / sửa nội dung: Todoist Android không sửa được comment (plan M8b, câu hỏi 1).
 */
public record CommentResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) UUID taskId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) UUID projectId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) CommentAuthor author,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String content,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) AttachmentResponse attachment,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ReactionSummary> reactions,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<UUID> mentionUserIds,
        @JsonProperty("isMine") @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Người đang xem là tác giả (chỉ xoá được comment của mình)") boolean isMine,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt) {
}
