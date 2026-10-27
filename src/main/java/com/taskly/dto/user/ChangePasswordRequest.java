package com.taskly.dto.user;

import com.taskly.common.validation.ValidPassword;
import jakarta.validation.constraints.NotNull;

/**
 * POST /api/v1/me/password. currentPassword không kiểm độ dài (sai thì 400 "Mật khẩu hiện tại không đúng");
 * newPassword theo @ValidPassword: 8–72 ký tự và ≤ 72 byte UTF-8 (giới hạn của BCrypt).
 */
public record ChangePasswordRequest(
        @NotNull(message = "{validation.current-password.required}")
        String currentPassword,

        @NotNull(message = "{validation.new-password.required}")
        @ValidPassword
        String newPassword) {
}
