package com.taskly.dto.oauth;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/** Theo RFC 6749 mục 5.1: tên trường snake_case. */
public record OAuthTokenResponse(
        @JsonProperty("access_token") @Schema(name = "access_token", requiredMode = Schema.RequiredMode.REQUIRED) String accessToken,
        @JsonProperty("token_type") @Schema(name = "token_type", requiredMode = Schema.RequiredMode.REQUIRED) String tokenType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String scope) {
}
