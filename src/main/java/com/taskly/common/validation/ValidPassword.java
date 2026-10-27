package com.taskly.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Luật mật khẩu MỚI duy nhất của hệ thống (register, me/password, password-reset/confirm): 8–72 ký tự và tối đa
 * 72 byte UTF-8 (giới hạn của BCrypt). Constraint ghép, KHÔNG gộp thành một violation → mỗi luật con báo key riêng.
 * null hợp lệ (để @NotNull của DTO lo). Không dùng cho mật khẩu hiện tại / mật khẩu đăng nhập.
 */
@Documented
@Constraint(validatedBy = {})
@Size(min = 8, max = 72, message = "{validation.password.size}")
@MaxUtf8Bytes(value = 72, message = "{validation.password.too-many-bytes}")
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPassword {

    /** Không dùng khi báo lỗi (các luật con tự báo); trỏ một key có thật để test quét key của DTO vẫn xanh. */
    String message() default "{validation.password.size}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
