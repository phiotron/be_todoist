package com.taskly.dto.template;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Save as template (M11): name vắng = tên dự án; description vắng = mô tả dự án. */
public record SaveTemplateRequest(
        @NotNull(message = "{error.project-id-required}")
        UUID projectId,

        @Size(min = 1, max = 120, message = "{error.template-name-size}")
        String name,

        @Size(max = 16383, message = "{error.description-size}")
        String description) {

    public SaveTemplateRequest {
        name = name == null ? null : name.strip();
    }
}
