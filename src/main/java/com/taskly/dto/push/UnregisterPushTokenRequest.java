package com.taskly.dto.push;

import io.swagger.v3.oas.annotations.media.Schema;

public record UnregisterPushTokenRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String token) {
}
