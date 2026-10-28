package com.taskly.dto.template;

import com.taskly.entity.TemplateCategory;
import io.swagger.v3.oas.annotations.media.Schema;

/** Template thư viện (M11, GET templates/library) — chữ theo Accept-Language (vi mặc định). */
public record LibraryTemplateResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String key,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) TemplateCategory category,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String description,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String color,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int sectionCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int taskCount) {
}
