package com.taskly.dto.auth;

import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(
        @NotBlank(message = "{validation.refresh-token.required}")
        String refreshToken) {
}
