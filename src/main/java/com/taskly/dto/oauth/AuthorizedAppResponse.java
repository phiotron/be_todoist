package com.taskly.dto.oauth;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

public record AuthorizedAppResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String clientId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> scopes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant authorizedAt,
        @Schema(description = "Lần cuối app dùng token, null nếu chưa dùng") Instant lastUsedAt) {
}
