package com.taskly.dto.template;

import com.taskly.entity.TemplateCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Xem trước template (M11): id = uuid (MINE) hoặc key (LIBRARY). */
public record TemplateDetailResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"MINE", "LIBRARY"}) String source,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, description = "null với template của mình")
        TemplateCategory category,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String description,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String color,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int sectionCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int taskCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Task không thuộc section")
        List<TemplateTaskItem> tasks,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<TemplateSectionItem> sections) {

    public static final String MINE = "MINE";
    public static final String LIBRARY = "LIBRARY";
}
