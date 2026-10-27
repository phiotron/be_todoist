package com.taskly.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Phút nhắc tự động trước giờ của task (spec 10, 12.1.1): chỉ nhận 0, 5, 10, 15, 30, 45, 60, 120, 180, 1440.
 * null hợp lệ (null = tắt auto reminder). Dùng ở hai chỗ: trên Integer, hoặc ở KIỂU PHẦN TỬ
 * Optional<@AutoReminderMinutes Integer> (cần TYPE_USE; Optional rỗng = hợp lệ). KHÔNG đặt trực tiếp trên
 * Optional<Integer>: Hibernate Validator không tự mở Optional → UnexpectedTypeException. message là key tra trong
 * messages_vi/en.properties (quy ước M3).
 */
@Documented
@Constraint(validatedBy = AutoReminderMinutesValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE,
        ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface AutoReminderMinutes {

    String message() default "{validation.auto-reminder-minutes.allowed}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
