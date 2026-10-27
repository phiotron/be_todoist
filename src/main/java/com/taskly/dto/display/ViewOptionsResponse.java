package com.taskly.dto.display;

import com.taskly.entity.DueFilter;
import com.taskly.entity.GroupBy;
import com.taskly.entity.SortBy;
import com.taskly.entity.SortDirection;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record ViewOptionsResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "TODAY | UPCOMING | project:<uuid> | label:<uuid> | filter:<uuid>") String viewKey,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) SortBy sortBy,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) SortDirection sortDirection,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) GroupBy groupBy,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Integer filterPriority,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) UUID filterLabelId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) DueFilter filterDue,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean showCompleted) {
}
