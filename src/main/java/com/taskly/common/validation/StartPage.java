package com.taskly.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Trang mở đầu hợp lệ (SettingsTargets.isValidStartPage). null hợp lệ (PATCH không gửi field). */
@Documented
@Constraint(validatedBy = StartPageValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface StartPage {

    String message() default "{validation.start-page.invalid}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
