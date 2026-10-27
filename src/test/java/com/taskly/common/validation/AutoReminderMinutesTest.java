package com.taskly.common.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.UnexpectedTypeException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AutoReminderMinutesTest {

    private static final String KEY = "{validation.auto-reminder-minutes.allowed}";

    /** Dạng thường: constraint trên Integer (null hợp lệ). */
    record Sample(@AutoReminderMinutes Integer minutes) {
    }

    /**
     * Cách UpdateSettingsRequest dùng: constraint ở KIỂU PHẦN TỬ của Optional (cùng mẫu Optional<@Min Integer> của
     * M1). Vắng = null, JSON null = Optional.empty().
     */
    record OptionalSample(Optional<@AutoReminderMinutes Integer> minutes) {
    }

    /** Đặt SAI chỗ (trên chính Optional): Hibernate Validator không tự mở Optional → UnexpectedTypeException. */
    record MisplacedSample(@AutoReminderMinutes Optional<Integer> minutes) {
    }

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void acceptsNull() {
        assertThat(validator.validate(new Sample(null))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 5, 10, 15, 30, 45, 60, 120, 180, 1440})
    void acceptsTheAllowedValues(int minutes) {
        assertThat(validator.validate(new Sample(minutes))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {-5, 1, 20, 90, 1439, 1441})
    void rejectsOtherValuesWithTheMessageKey(int minutes) {
        assertThat(validator.validate(new Sample(minutes)))
                .extracting(ConstraintViolation::getMessageTemplate)
                .containsExactly(KEY);
    }

    @Test
    void absentAndEmptyOptionalAreValid() {
        assertThat(validator.validate(new OptionalSample(null))).isEmpty();
        assertThat(validator.validate(new OptionalSample(Optional.empty()))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 5, 10, 15, 30, 45, 60, 120, 180, 1440})
    void optionalAcceptsTheAllowedValues(int minutes) {
        assertThat(validator.validate(new OptionalSample(Optional.of(minutes)))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {-5, 1, 20, 90, 1439, 1441})
    void optionalRejectsOtherValuesWithTheMessageKey(int minutes) {
        assertThat(validator.validate(new OptionalSample(Optional.of(minutes))))
                .extracting(ConstraintViolation::getMessageTemplate)
                .containsExactly(KEY);
    }

    /** Khoá lý do của TYPE_USE: đặt trên chính Optional<Integer> không dùng được. */
    @Test
    void constraintOnTheOptionalItselfThrowsUnexpectedType() {
        assertThatThrownBy(() -> validator.validate(new MisplacedSample(Optional.of(30))))
                .isInstanceOf(UnexpectedTypeException.class);
    }
}
