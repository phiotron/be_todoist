package com.taskly.dto.auth;

import com.taskly.common.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** POST /api/v1/auth/password-reset/confirm. newPassword theo luật đăng ký (8–72 ký tự, ≤ 72 byte UTF-8). */
public record ResetPasswordRequest(
        @NotBlank(message = "{validation.reset-token.required}")
        String token,

        @NotNull(message = "{validation.new-password.required}")
        @Size(min = 8, max = 72, message = "{validation.password.size}")
        @MaxUtf8Bytes(value = 72, message = "{validation.password.too-many-bytes}")
        String newPassword) {

    /** Mã dán từ email có thể dính khoảng trắng / xuống dòng ở hai đầu. */
    public ResetPasswordRequest {
        token = token == null ? null : token.strip();
    }
}
