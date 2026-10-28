package com.taskly.dto.template;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Một section trong template (M11) và task gốc của nó. */
public record TemplateSectionItem(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "\"\" khi không có mô tả") String description,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<TemplateTaskItem> tasks) {

    public TemplateSectionItem {
        description = description == null ? "" : description;
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }
}
