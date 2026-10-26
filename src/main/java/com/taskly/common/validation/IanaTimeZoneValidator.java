package com.taskly.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.ZoneId;
import java.util.Set;

public class IanaTimeZoneValidator implements ConstraintValidator<IanaTimeZone, String> {

    /** Chép một lần: ZoneId.getAvailableZoneIds() tạo Set mới mỗi lần gọi. */
    private static final Set<String> ZONE_IDS = Set.copyOf(ZoneId.getAvailableZoneIds());

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || ZONE_IDS.contains(value);
    }
}
