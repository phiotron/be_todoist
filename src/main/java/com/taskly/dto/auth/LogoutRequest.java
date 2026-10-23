package com.taskly.dto.auth;

import jakarta.validation.constraints.NotBlank;

public record LogoutRequest(
        @NotBlank(message = "Thiếu refresh token")
        String refreshToken) {
}
