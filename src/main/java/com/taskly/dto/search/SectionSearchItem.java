package com.taskly.dto.search;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record SectionSearchItem(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID projectId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String projectName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name) {
}
