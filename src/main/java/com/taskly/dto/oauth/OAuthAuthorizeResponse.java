package com.taskly.dto.oauth;

import io.swagger.v3.oas.annotations.media.Schema;

public record OAuthAuthorizeResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "redirectUri kèm code (và state); app mở URL này") String redirectUrl) {
}
