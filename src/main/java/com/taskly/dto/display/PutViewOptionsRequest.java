package com.taskly.dto.display;

import com.taskly.common.error.ErrorMessage;
import com.taskly.entity.DueFilter;
import com.taskly.entity.GroupBy;
import com.taskly.entity.Layout;
import com.taskly.entity.SortBy;
import com.taskly.entity.SortDirection;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record PutViewOptionsRequest(
        @NotNull(message = "{error.display-invalid}") @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        SortBy sortBy,
        @NotNull(message = "{error.display-invalid}") @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        SortDirection sortDirection,
        @NotNull(message = "{error.display-invalid}") @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        GroupBy groupBy,
        @Schema(description = "1–4") Integer filterPriority,
        @Schema(description = "Label của mình") UUID filterLabelId,
        DueFilter filterDue,
        @Schema(description = "Công tắc Completed tasks: dự án, Today, Upcoming (M7)") Boolean showCompleted,
        @Schema(description = "M7: LIST | BOARD | CALENDAR; vắng = giữ giá trị đang lưu (bản ghi mới = LIST)")
        Layout layout,
        @Schema(description = "M7: công tắc Future occurrences, chỉ Upcoming; vắng = giữ") Boolean showFutureOccurrences) {
}
