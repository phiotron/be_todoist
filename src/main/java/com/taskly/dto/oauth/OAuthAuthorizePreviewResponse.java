package com.taskly.dto.oauth;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record OAuthAuthorizePreviewResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String clientId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String appName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String redirectUri,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> scopes) {
}
