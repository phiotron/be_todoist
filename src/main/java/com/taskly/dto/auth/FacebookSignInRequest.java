package com.taskly.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST auth/facebook (M13b): user access token Facebook (luồng web, nhận qua deep link sau auth/facebook/callback). */
public record FacebookSignInRequest(
        @NotBlank(message = "{validation.identity.token.required}")
        @Size(max = 4096, message = "{validation.identity.token.too-long}")
        String accessToken) {
}
