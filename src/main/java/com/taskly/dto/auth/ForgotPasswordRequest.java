package com.taskly.dto.auth;

import com.taskly.common.EmailNormalizer;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /api/v1/auth/password-reset/request. Email sai định dạng → 400; đúng định dạng thì luôn 200. */
public record ForgotPasswordRequest(
        @NotBlank(message = "{validation.email.required}")
        @Email(message = "{validation.email.invalid}")
        @Size(max = 254, message = "{validation.email.too-long}")
        String email) {

    /** Chuẩn hoá TRƯỚC khi validate: strip + chữ thường (giống đăng ký / đăng nhập). */
    public ForgotPasswordRequest {
        email = EmailNormalizer.normalize(email);
    }
}
