package com.taskly.dto.template;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Một task trong template (M11): không có ngày / nhắc / label (plan M11, câu hỏi mở 3); children = sub-task. */
public record TemplateTaskItem(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String content,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "\"\" khi không có mô tả") String description,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "1 (cao nhất) … 4") int priority,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<TemplateTaskItem> children) {

    public TemplateTaskItem {
        description = description == null ? "" : description;
        priority = priority < 1 || priority > 4 ? 4 : priority;
        children = children == null ? List.of() : List.copyOf(children);
    }

    /** Số task kể cả mọi con cháu. */
    public int count() {
        return 1 + children.stream().mapToInt(TemplateTaskItem::count).sum();
    }
}
