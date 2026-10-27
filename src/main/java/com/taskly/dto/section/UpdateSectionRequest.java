package com.taskly.dto.section;

import com.taskly.common.error.ProblemTexts;
import jakarta.validation.constraints.Size;

/** Field null = không đổi; description "" = xoá mô tả. Section dùng "ghi sau thắng" (spec 5.6). */
public record UpdateSectionRequest(
        @Size(min = 1, max = 2048, message = ProblemTexts.SECTION_NAME_SIZE)
        String name,

        @Size(max = 16383, message = ProblemTexts.DESCRIPTION_SIZE)
        String description) {

    public UpdateSectionRequest {
        name = name == null ? null : name.strip();
    }
}
