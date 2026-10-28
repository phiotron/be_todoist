package com.taskly.dto.template;

import com.taskly.entity.ProjectColor;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Apply a template (M11): projectId vắng → tạo dự án gốc mới (name vắng = tên template, color vắng = màu template) → 201;
 * có projectId → thêm section / task vào cuối dự án đó (name / color bỏ qua) → 200.
 */
public record ApplyTemplateRequest(
        @Schema(type = "string", format = "uuid", nullable = true, description = "Vắng = tạo dự án mới")
        UUID projectId,

        @Size(min = 1, max = 120, message = "{error.project-name-size}")
        String name,

        ProjectColor color) {

    public ApplyTemplateRequest {
        name = name == null ? null : name.strip();
    }
}
