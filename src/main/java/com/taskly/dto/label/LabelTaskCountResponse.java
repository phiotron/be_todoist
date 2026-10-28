package com.taskly.dto.label;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** GET labels/counts: số việc chưa xong mang nhãn này mà user đọc được (như tasks?labelId=). */
public record LabelTaskCountResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID labelId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long taskCount) {
}
