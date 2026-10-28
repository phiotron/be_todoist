package com.taskly.dto.push;

import io.swagger.v3.oas.annotations.media.Schema;

public record RegisterPushTokenRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Device token FCM, 1-4096 ký tự") String token,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "ANDROID") String platform) {
}
