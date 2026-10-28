package com.taskly.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Set;

public class SnoozeMinutesValidator implements ConstraintValidator<SnoozeMinutes, Integer> {

    /** Khớp CHECK ck_users_snooze_minutes của V16. */
    public static final Set<Integer> ALLOWED_MINUTES = Set.of(5, 10, 15, 30, 45, 60, 120, 180);

    @Override
    public boolean isValid(Integer value, ConstraintValidatorContext context) {
        return value == null || ALLOWED_MINUTES.contains(value);
    }
}
