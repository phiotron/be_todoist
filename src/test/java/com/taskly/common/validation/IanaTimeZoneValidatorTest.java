package com.taskly.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit thuần cho múi giờ IANA (users.time_zone, spec mục 5.2). */
class IanaTimeZoneValidatorTest {

    private final IanaTimeZoneValidator validator = new IanaTimeZoneValidator();

    @ParameterizedTest
    @ValueSource(strings = {"Asia/Ho_Chi_Minh", "Asia/Saigon", "Europe/London", "America/New_York", "UTC"})
    void acceptsIanaZoneIds(String zone) {
        assertThat(validator.isValid(zone, null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Mars/Base", "+07:00", "GMT+7", "asia/ho_chi_minh", "Asia/Ho_Chi_Minh "})
    void rejectsOffsetsUnknownOrMiscasedIds(String zone) {
        assertThat(validator.isValid(zone, null)).isFalse();
    }

    @Test
    void nullIsValid_soPatchCanOmitTheField() {
        assertThat(validator.isValid(null, null)).isTrue();
    }
}
