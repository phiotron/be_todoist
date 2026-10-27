package com.taskly.dto.twofactor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {code}: mã 6 số TOTP (enable chỉ nhận TOTP) hoặc mã khôi phục (recovery-codes). */
public record TwoFactorCodeRequest(
        @NotBlank(message = "{validation.two-factor-code.required}")
        @Size(max = 32, message = "{validation.two-factor-code.too-long}")
        String code) {
}
