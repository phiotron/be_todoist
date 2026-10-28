package com.taskly.dto.sharing;

import com.taskly.entity.WorkspaceRole;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * M8a: một lời mời email. kind = PROJECT (projectId, targetName = tên dự án) | WORKSPACE (workspaceId, targetName = tên
 * nhóm). Không bao giờ chứa token.
 */
public record InvitationResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"PROJECT", "WORKSPACE"}) String kind,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) UUID projectId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) UUID workspaceId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String targetName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String email,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) WorkspaceRole role,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String invitedByName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant expiresAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "true khi người nhận bật Auto accept invites và đã được thêm ngay") boolean accepted) {
}
