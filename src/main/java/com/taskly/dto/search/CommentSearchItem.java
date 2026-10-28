package com.taskly.dto.search;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** Comment khớp từ khoá ở search/objects (M8b). taskId null = comment của dự án. */
public record CommentSearchItem(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) UUID taskId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID projectId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String projectName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String taskContent,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String content) {
}
