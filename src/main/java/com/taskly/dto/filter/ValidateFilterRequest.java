package com.taskly.dto.filter;

import com.taskly.common.error.ErrorMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record ValidateFilterRequest(
        @NotNull(message = "{error.filter-query-size}")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String query) {
}
