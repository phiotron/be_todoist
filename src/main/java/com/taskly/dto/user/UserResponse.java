package com.taskly.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** Thông tin user trả cho client — KHÔNG có passwordHash. Mọi field luôn có mặt trong response. */
public record UserResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String email,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String displayName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String locale,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String timeZone,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Đã bật xác minh hai bước (M3)")
        boolean twoFactorEnabled) {
}
