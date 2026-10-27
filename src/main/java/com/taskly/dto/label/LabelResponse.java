package com.taskly.dto.label;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.taskly.entity.ProjectColor;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record LabelResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ProjectColor color,
        @JsonProperty("isFavorite") @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isFavorite,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long sortOrder) {
}
