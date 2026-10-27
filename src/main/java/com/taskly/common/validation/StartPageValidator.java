package com.taskly.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class StartPageValidator implements ConstraintValidator<StartPage, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || SettingsTargets.isValidStartPage(value);
    }
}
