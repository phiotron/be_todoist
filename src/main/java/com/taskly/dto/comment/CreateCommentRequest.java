package com.taskly.dto.comment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * POST tasks/{id}/comments | projects/{id}/comments (M8b). content cắt khoảng trắng hai đầu, ≤ 15 000 ký tự; rỗng chỉ
 * được khi có attachmentId (else 400 error.comment-empty). attachmentId = file đã tải lên bằng POST attachments.
 * mentionUserIds: người được @mention (≤ 50) — phải xem được task / dự án (else 400 error.mention-invalid).
 */
public record CreateCommentRequest(
        @Schema(description = "id do client sinh (spec 12.11)") UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = CreateCommentRequest.MAX_CONTENT)
        @Size(max = CreateCommentRequest.MAX_CONTENT, message = "{validation.comment.content.size}")
        String content,
        UUID attachmentId,
        @Size(max = CreateCommentRequest.MAX_MENTIONS, message = "{validation.comment.mentions.size}")
        List<UUID> mentionUserIds) {

    public static final int MAX_CONTENT = 15_000;
    public static final int MAX_MENTIONS = 50;
}
