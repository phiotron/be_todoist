package com.taskly.dto.label;

import com.taskly.entity.ProjectColor;
import io.swagger.v3.oas.annotations.media.Schema;

/** Field vắng hoặc null = giữ nguyên. */
public record UpdateLabelRequest(
        @Schema(description = "Tên mới (quy tắc như khi tạo)") String name,
        @Schema(description = "Khoá màu mới") ProjectColor color) {
}
