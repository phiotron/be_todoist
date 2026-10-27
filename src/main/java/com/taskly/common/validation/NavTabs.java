package com.taskly.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Thanh tab hợp lệ (SettingsTargets.isValidNavTabs). null hợp lệ (PATCH không gửi field). */
@Documented
@Constraint(validatedBy = NavTabsValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface NavTabs {

    String message() default "{validation.nav-tabs.invalid}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
