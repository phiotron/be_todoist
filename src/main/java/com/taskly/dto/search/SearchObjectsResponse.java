package com.taskly.dto.search;

import com.taskly.dto.filter.FilterResponse;
import com.taskly.dto.label.LabelResponse;
import com.taskly.dto.project.ProjectResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Mỗi loại tối đa 20, sắp theo độ giống giảm dần (M8 thêm comments). */
public record SearchObjectsResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ProjectResponse> projects,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<SectionSearchItem> sections,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<LabelResponse> labels,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<FilterResponse> filters,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "M8b: comment khớp, mới trước")
        List<CommentSearchItem> comments) {

    public static SearchObjectsResponse empty() {
        return new SearchObjectsResponse(List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
