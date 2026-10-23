package com.taskly.dto.auth;

import com.taskly.common.EmailNormalizer;
import com.taskly.common.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Message tiếng Việt đặt thẳng trên annotation: app mobile hiển thị nguyên văn errors[].message.
 * Tên component = tên field JSON camelCase = errors[].field.
 */
public record RegisterRequest(
        @NotBlank(message = "Vui lòng nhập email")
        @Email(message = "Email không hợp lệ")
        @Size(max = 254, message = "Email tối đa 254 ký tự")
        String email,

        @NotNull(message = "Vui lòng nhập mật khẩu")
        @Size(min = 8, max = 72, message = "Mật khẩu phải từ 8 đến 72 ký tự")
        @MaxUtf8Bytes(value = 72, message = "Mật khẩu quá dài, hãy dùng mật khẩu ngắn hơn")
        String password,

        @NotBlank(message = "Vui lòng nhập tên hiển thị")
        @Size(max = 100, message = "Tên hiển thị tối đa 100 ký tự")
        String displayName) {

    /** Chuẩn hoá TRƯỚC khi validate (Jackson gọi constructor này): email strip + chữ thường, tên strip. */
    public RegisterRequest {
        email = EmailNormalizer.normalize(email);
        displayName = displayName == null ? null : displayName.strip();
    }
}
