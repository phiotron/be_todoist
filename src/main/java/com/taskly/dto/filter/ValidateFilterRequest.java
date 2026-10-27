package com.taskly.dto.filter;

import com.taskly.common.error.ProblemTexts;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record ValidateFilterRequest(
        @NotNull(message = ProblemTexts.FILTER_QUERY_SIZE)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String query) {
}
