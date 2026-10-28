package com.taskly.dto.workspace;

import com.taskly.entity.WorkspaceRole;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** M8a: một người trong People của workspace nhóm. */
public record WorkspaceMemberResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID userId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String email,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String displayName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) WorkspaceRole role,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant joinedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                description = "Đường dẫn ảnh đại diện (GET, cần Bearer) hoặc null = chưa có ảnh (như UserResponse.avatarUrl)")
        String avatarUrl) {
}
