package com.taskly.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.List;

public class QuickAddChipsValidator implements ConstraintValidator<QuickAddChips, List<String>> {

    @Override
    public boolean isValid(List<String> value, ConstraintValidatorContext context) {
        return value == null || QuickAddChipRules.isValid(value);
    }
}
