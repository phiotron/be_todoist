package com.taskly.dto.display;

import com.taskly.common.error.ProblemTexts;
import com.taskly.entity.DueFilter;
import com.taskly.entity.GroupBy;
import com.taskly.entity.SortBy;
import com.taskly.entity.SortDirection;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record PutViewOptionsRequest(
        @NotNull(message = ProblemTexts.DISPLAY_INVALID) @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        SortBy sortBy,
        @NotNull(message = ProblemTexts.DISPLAY_INVALID) @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        SortDirection sortDirection,
        @NotNull(message = ProblemTexts.DISPLAY_INVALID) @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        GroupBy groupBy,
        @Schema(description = "1–4") Integer filterPriority,
        @Schema(description = "Label của mình") UUID filterLabelId,
        DueFilter filterDue,
        @Schema(description = "Chỉ view dự án (công tắc Completed tasks)") Boolean showCompleted) {
}
