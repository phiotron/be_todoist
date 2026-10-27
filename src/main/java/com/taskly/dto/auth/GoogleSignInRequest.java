package com.taskly.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** ID token Google lấy từ Credential Manager ở app. Giới hạn 4096 ký tự để chặn body bất thường. */
public record GoogleSignInRequest(
        @NotBlank(message = "{validation.google.id-token.required}")
        @Size(max = 4096, message = "{validation.google.token.too-long}")
        String idToken) {
}
