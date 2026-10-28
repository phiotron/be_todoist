package com.taskly.dto.oauth;

import io.swagger.v3.oas.annotations.media.Schema;

public record OAuthAuthorizeRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String clientId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String redirectUri,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Scope cách nhau bởi dấu phẩy hoặc khoảng trắng") String scope,
        @Schema(description = "Giữ nguyên và trả lại trong redirectUrl") String state,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "PKCE: base64url(SHA-256(code_verifier)), 43-128 ký tự") String codeChallenge,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Chỉ S256") String codeChallengeMethod) {
}
