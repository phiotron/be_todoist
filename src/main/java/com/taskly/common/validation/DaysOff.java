package com.taskly.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Ngày nghỉ (M10, Settings → Productivity): các ngày ISO 1–7 khác nhau, tối đa 6 ngày (nghỉ cả tuần thì không còn ngày
 * nào tính streak). null hợp lệ (PATCH không gửi field); [] = không nghỉ ngày nào.
 */
@Documented
@Constraint(validatedBy = DaysOffValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DaysOff {

    String message() default "{validation.days-off.invalid}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
