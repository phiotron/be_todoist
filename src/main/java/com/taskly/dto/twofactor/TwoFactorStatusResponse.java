package com.taskly.dto.twofactor;

import io.swagger.v3.oas.annotations.media.Schema;

/** GET me/2fa. recoveryCodesLeft = số mã khôi phục chưa dùng (0 khi chưa bật). */
public record TwoFactorStatusResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean enabled,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int recoveryCodesLeft) {
}
