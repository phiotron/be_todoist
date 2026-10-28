package com.taskly.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Mục Navigation menu hợp lệ (M3x, NavMenuItemsValidator). null hợp lệ (PATCH không gửi field). */
@Documented
@Constraint(validatedBy = NavMenuItemsValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface NavMenuItems {

    String message() default "{validation.nav-menu-items.invalid}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
