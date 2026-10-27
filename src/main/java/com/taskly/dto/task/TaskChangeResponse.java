package com.taskly.dto.task;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Kết quả close / reopen (spec 12.3): task sau thao tác + các task KHÁC bị đổi theo do lan truyền (`cascaded` KHÔNG gồm
 * chính task). Thay hẳn `cascadedTaskIds` của bản 3.
 */
public record TaskChangeResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) TaskResponse task,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<CascadedTask> cascaded) {
}
