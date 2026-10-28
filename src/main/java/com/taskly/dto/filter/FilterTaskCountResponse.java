package com.taskly.dto.filter;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** GET filters/counts: số việc chưa xong (không trùng) khớp ít nhất một phần của query, trong phạm vi đọc của user. */
public record FilterTaskCountResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID filterId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long taskCount) {
}
