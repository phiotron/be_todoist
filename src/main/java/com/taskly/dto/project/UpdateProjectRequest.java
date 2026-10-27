package com.taskly.dto.project;

import com.taskly.common.error.ProblemTexts;
import com.taskly.entity.ProjectColor;
import jakarta.validation.constraints.Size;

/**
 * Field null = không đổi; description "" = xoá mô tả. Dự án dùng "ghi sau thắng" (không version) — spec 5.6.
 * Đổi cha dùng POST projects/{id}/move (Task 13).
 */
public record UpdateProjectRequest(
        @Size(min = 1, max = 120, message = ProblemTexts.PROJECT_NAME_SIZE)
        String name,

        ProjectColor color,

        @Size(max = 16383, message = ProblemTexts.DESCRIPTION_SIZE)
        String description) {

    public UpdateProjectRequest {
        name = name == null ? null : name.strip();
    }
}
