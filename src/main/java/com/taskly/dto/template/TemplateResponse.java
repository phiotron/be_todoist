package com.taskly.dto.template;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** Template của mình (M11, GET templates / POST templates). */
public record TemplateResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "\"\" khi không có mô tả") String description,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Khoá màu của dự án tạo từ template") String color,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int sectionCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Số task kể cả sub-task") int taskCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant updatedAt) {
}
