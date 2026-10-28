package com.taskly.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Số phút "Hoãn" của thông báo nhắc (M12, Todoist "When snoozed"): chỉ nhận 5, 10, 15, 30, 45, 60, 120, 180 [CXM].
 * null hợp lệ (field vắng / null = giữ nguyên, quy ước PATCH me/settings).
 */
@Documented
@Constraint(validatedBy = SnoozeMinutesValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE,
        ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface SnoozeMinutes {

    String message() default "{validation.snooze-minutes.invalid}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
