package com.taskly.dto.section;

import jakarta.validation.constraints.Size;

/** Field null = không đổi; description "" = xoá mô tả. Section dùng "ghi sau thắng" (spec 5.6). */
public record UpdateSectionRequest(
        @Size(min = 1, max = 2048, message = "{error.section-name-size}")
        String name,

        @Size(max = 16383, message = "{error.description-size}")
        String description) {

    public UpdateSectionRequest {
        name = name == null ? null : name.strip();
    }
}
