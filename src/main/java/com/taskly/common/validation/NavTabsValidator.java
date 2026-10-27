package com.taskly.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.List;

public class NavTabsValidator implements ConstraintValidator<NavTabs, List<String>> {

    @Override
    public boolean isValid(List<String> value, ConstraintValidatorContext context) {
        return value == null || SettingsTargets.isValidNavTabs(value);
    }
}
