package com.taskly.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public class DaysOffValidator implements ConstraintValidator<DaysOff, List<Integer>> {

    /** Nghỉ cả 7 ngày → không còn ngày nào tính streak. */
    static final int MAX_DAYS_OFF = 6;

    @Override
    public boolean isValid(List<Integer> value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return value.size() <= MAX_DAYS_OFF
                && value.stream().allMatch(day -> Objects.nonNull(day) && day >= 1 && day <= 7)
                && new HashSet<>(value).size() == value.size();
    }
}
