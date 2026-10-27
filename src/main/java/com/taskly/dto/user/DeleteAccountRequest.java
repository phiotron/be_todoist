package com.taskly.dto.user;

import jakarta.validation.constraints.Size;

/**
 * DELETE /api/v1/me. password không đánh @NotNull: tài khoản chưa có mật khẩu (M5) phải nhận 409 password-required chứ
 * không phải 400 — service kiểm theo thứ tự. code chỉ cần khi 2FA đang bật (TOTP hoặc mã khôi phục).
 */
public record DeleteAccountRequest(
        @Size(max = 256, message = "{validation.password.too-many-bytes}")
        String password,

        @Size(max = 32, message = "{validation.two-factor-code.too-long}")
        String code) {
}
