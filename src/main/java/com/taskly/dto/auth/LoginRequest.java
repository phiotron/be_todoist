package com.taskly.dto.auth;

import com.taskly.common.EmailNormalizer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Không kiểm độ dài mật khẩu ở đây để không lộ quy tắc; sai thì luôn trả 401 chung. */
public record LoginRequest(
        @NotBlank(message = "Vui lòng nhập email")
        String email,

        @NotNull(message = "Vui lòng nhập mật khẩu")
        String password) {

    public LoginRequest {
        email = EmailNormalizer.normalize(email);
    }
}
