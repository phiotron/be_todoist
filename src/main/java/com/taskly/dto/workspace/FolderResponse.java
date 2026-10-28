package com.taskly.dto.workspace;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** M8a: folder của workspace nhóm. */
public record FolderResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID workspaceId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long sortOrder) {
}
