package com.taskly.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** ID token Google lấy từ Credential Manager ở app. Giới hạn 4096 ký tự để chặn body bất thường. */
public record GoogleSignInRequest(
        @NotBlank(message = "Thiếu idToken")
        @Size(max = 4096, message = "idToken quá dài")
        String idToken) {
}
