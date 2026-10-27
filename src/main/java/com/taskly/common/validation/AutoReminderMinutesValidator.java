package com.taskly.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Set;

public class AutoReminderMinutesValidator implements ConstraintValidator<AutoReminderMinutes, Integer> {

    /** Khớp CHECK ck_users_auto_reminder_minutes của V5. */
    public static final Set<Integer> ALLOWED_MINUTES = Set.of(0, 5, 10, 15, 30, 45, 60, 120, 180, 1440);

    @Override
    public boolean isValid(Integer value, ConstraintValidatorContext context) {
        return value == null || ALLOWED_MINUTES.contains(value);
    }
}
