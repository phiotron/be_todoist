package com.taskly.dto.twofactor;

import io.swagger.v3.oas.annotations.media.Schema;

/** POST me/2fa/setup: secret base32 (nhập tay) + otpauthUri (QR) cho app authenticator. Chỉ hiển thị, không log. */
public record TwoFactorSetupResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Base32, 32 ký tự") String secret,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "otpauth://totp/Taskly:<email>?…")
        String otpauthUri) {
}
