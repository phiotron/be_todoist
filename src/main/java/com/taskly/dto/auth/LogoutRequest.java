package com.taskly.dto.auth;

import jakarta.validation.constraints.NotBlank;

public record LogoutRequest(
        @NotBlank(message = "{validation.refresh-token.required}")
        String refreshToken) {
}
