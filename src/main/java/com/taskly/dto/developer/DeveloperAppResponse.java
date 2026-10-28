package com.taskly.dto.developer;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record DeveloperAppResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String clientId,
        @Schema(description = "Chỉ có khi tạo app (POST), hiện một lần; các API khác trả null") String clientSecret,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String redirectUri,
        @Schema(description = "Webhook của app, null nếu chưa đặt") WebhookResponse webhook,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt) {
}
