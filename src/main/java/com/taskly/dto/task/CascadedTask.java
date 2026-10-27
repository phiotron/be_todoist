package com.taskly.dto.task;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * Một task KHÁC bị đổi trạng thái do lan truyền (spec 5.6, 12.3): version mới để app cập nhật cache mà không tải lại;
 * completedAt null nghĩa là task đã được mở lại.
 */
public record CascadedTask(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long version,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                description = "null = đã mở lại") Instant completedAt) {
}
