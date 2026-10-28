package com.taskly.dto.workspace;

import com.taskly.entity.ProjectAccess;
import com.taskly.entity.ProjectColor;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** M8a Team Discovery: một dự án chưa lưu trữ người gọi thấy được trong workspace nhóm. */
public record TeamProjectResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ProjectColor color,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) UUID folderId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ProjectAccess access,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long memberCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long activeTaskCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean joined) {
}
