package com.taskly.dto.reorder;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** sort_order mới của phần tử; nếu vùng chứa bị đánh số lại, app tải lại danh sách để có thứ tự mới. */
public record ReorderResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReorderType type,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long sortOrder) {
}
