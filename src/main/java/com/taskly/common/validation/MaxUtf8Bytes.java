package com.taskly.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Giới hạn độ dài chuỗi theo SỐ BYTE UTF-8 (không phải số ký tự).
 * Dùng cho mật khẩu: BCrypt chỉ nhận tối đa 72 byte, chữ tiếng Việt có dấu tốn 2–3 byte/ký tự.
 * null được coi là hợp lệ (để @NotNull lo).
 */
@Documented
@Constraint(validatedBy = MaxUtf8BytesValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxUtf8Bytes {

    int value();

    String message() default "Giá trị vượt quá số byte cho phép";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
