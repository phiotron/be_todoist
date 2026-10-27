package com.taskly.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST auth/2fa/verify: twoFactorToken từ 401 two-factor-required + mã 6 số TOTP hoặc mã khôi phục. */
public record TwoFactorVerifyRequest(
        @NotBlank(message = "{validation.two-factor-token.required}")
        @Size(max = 2048, message = "{validation.two-factor-token.too-long}")
        String twoFactorToken,

        @NotBlank(message = "{validation.two-factor-code.required}")
        @Size(max = 32, message = "{validation.two-factor-code.too-long}")
        String code) {
}
