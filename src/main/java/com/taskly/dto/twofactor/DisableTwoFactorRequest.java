package com.taskly.dto.twofactor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST me/2fa/disable. password bắt buộc nếu tài khoản có mật khẩu (service kiểm — từ M5 có tài khoản chỉ Google);
 * code = TOTP hoặc mã khôi phục.
 */
public record DisableTwoFactorRequest(
        @Size(max = 256, message = "{validation.password.too-many-bytes}")
        String password,

        @NotBlank(message = "{validation.two-factor-code.required}")
        @Size(max = 32, message = "{validation.two-factor-code.too-long}")
        String code) {
}
