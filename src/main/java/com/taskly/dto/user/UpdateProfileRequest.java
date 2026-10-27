package com.taskly.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** PATCH /api/v1/me: tên hiển thị 1–100 ký tự sau khi cắt khoảng trắng hai đầu (spec mục 12.1). */
public record UpdateProfileRequest(
        @NotBlank(message = "{validation.display-name.required}")
        @Size(max = 100, message = "{validation.display-name.too-long}")
        String displayName) {

    /** Cắt khoảng trắng TRƯỚC khi validate (Jackson gọi constructor này). */
    public UpdateProfileRequest {
        displayName = displayName == null ? null : displayName.strip();
    }
}
