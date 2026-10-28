package com.taskly.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST auth/apple (M13b). idToken lấy từ luồng web "Sign in with Apple" (deep link sau auth/apple/callback). Apple chỉ
 * gửi tên người dùng ở lần cho phép đầu tiên → app chuyển tiếp vào displayName (tuỳ chọn) để đặt tên tài khoản mới.
 */
public record AppleSignInRequest(
        @NotBlank(message = "{validation.identity.token.required}")
        @Size(max = 4096, message = "{validation.identity.token.too-long}")
        String idToken,

        @Schema(description = "Tên Apple trả ở lần cho phép đầu tiên (tuỳ chọn)")
        @Size(max = 200, message = "{validation.identity.display-name.too-long}")
        String displayName) {
}
