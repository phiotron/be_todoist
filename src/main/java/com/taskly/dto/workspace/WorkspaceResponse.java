package com.taskly.dto.workspace;

import com.taskly.entity.WorkspaceRole;
import com.taskly.entity.WorkspaceType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** M8a: một workspace của người gọi (cá nhân hoặc nhóm) + vai trò của người gọi trong đó. */
public record WorkspaceResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) WorkspaceType type,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) WorkspaceRole role,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long memberCount) {
}
