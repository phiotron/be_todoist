package com.taskly.dto.auth;

import com.taskly.common.validation.ValidPassword;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** POST /api/v1/auth/password-reset/confirm. newPassword theo @ValidPassword (8–72 ký tự, ≤ 72 byte UTF-8). */
public record ResetPasswordRequest(
        @NotBlank(message = "{validation.reset-token.required}")
        String token,

        @NotNull(message = "{validation.new-password.required}")
        @ValidPassword
        String newPassword) {

    /** Mã dán từ email có thể dính khoảng trắng / xuống dòng ở hai đầu. */
    public ResetPasswordRequest {
        token = token == null ? null : token.strip();
    }
}
