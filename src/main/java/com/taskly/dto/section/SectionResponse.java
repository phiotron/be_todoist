package com.taskly.dto.section;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** Spec 12.2. */
public record SectionResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID projectId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "\"\" khi không có mô tả") String description,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long sortOrder,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                description = "khác null = section lưu trữ (xám, cuối dự án, task chỉ đọc)") Instant archivedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Số task ĐANG MỞ (gồm sub-task) trong section") long activeTaskCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant updatedAt) {
}
