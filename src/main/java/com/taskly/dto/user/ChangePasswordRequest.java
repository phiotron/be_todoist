package com.taskly.dto.user;

import com.taskly.common.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * POST /api/v1/me/password. currentPassword không kiểm độ dài (sai thì 400 "Mật khẩu hiện tại không đúng");
 * newPassword theo luật đăng ký: 8–72 ký tự và ≤ 72 byte UTF-8 (giới hạn của BCrypt).
 */
public record ChangePasswordRequest(
        @NotNull(message = "{validation.current-password.required}")
        String currentPassword,

        @NotNull(message = "{validation.new-password.required}")
        @Size(min = 8, max = 72, message = "{validation.password.size}")
        @MaxUtf8Bytes(value = 72, message = "{validation.password.too-many-bytes}")
        String newPassword) {
}
