package com.taskly.dto.auth;

import com.taskly.common.EmailNormalizer;
import com.taskly.common.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * message = "{key}" trong messages_vi/en.properties: errors[].message được dịch theo Accept-Language, app mobile
 * hiển thị nguyên văn. Tên component = tên field JSON camelCase = errors[].field.
 */
public record RegisterRequest(
        @NotBlank(message = "{validation.email.required}")
        @Email(message = "{validation.email.invalid}")
        @Size(max = 254, message = "{validation.email.too-long}")
        String email,

        @NotNull(message = "{validation.password.required}")
        @Size(min = 8, max = 72, message = "{validation.password.size}")
        @MaxUtf8Bytes(value = 72, message = "{validation.password.too-many-bytes}")
        String password,

        @NotBlank(message = "{validation.display-name.required}")
        @Size(max = 100, message = "{validation.display-name.too-long}")
        String displayName) {

    /** Chuẩn hoá TRƯỚC khi validate (Jackson gọi constructor này): email strip + chữ thường, tên strip. */
    public RegisterRequest {
        email = EmailNormalizer.normalize(email);
        displayName = displayName == null ? null : displayName.strip();
    }
}
