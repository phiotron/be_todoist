package com.taskly.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Chuỗi phải là id múi giờ IANA mà JDK biết (vd Asia/Ho_Chi_Minh); phân biệt hoa thường; không nhận offset như +07:00.
 * null được coi là hợp lệ (PATCH không gửi field; @NotNull lo nếu bắt buộc).
 */
@Documented
@Constraint(validatedBy = IanaTimeZoneValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface IanaTimeZone {

    String message() default "{validation.time-zone.invalid}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
