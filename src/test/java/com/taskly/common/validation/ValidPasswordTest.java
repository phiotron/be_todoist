package com.taskly.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Unit thuần: luật mật khẩu chung 8–72 ký tự và ≤ 72 byte UTF-8 (spec 5.9, review I18). */
class ValidPasswordTest {

    record Holder(@ValidPassword String password) {
    }

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private Set<String> templatesFor(String password) {
        Set<ConstraintViolation<Holder>> violations = validator.validate(new Holder(password));
        return violations.stream().map(ConstraintViolation::getMessageTemplate)
                .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void eightAndSeventyTwoAsciiCharacters_areValid() {
        assertThat(templatesFor("a".repeat(8))).isEmpty();
        assertThat(templatesFor("a".repeat(72))).isEmpty();
    }

    @Test
    void nullIsLeftToNotNull() {
        assertThat(templatesFor(null)).isEmpty();
    }

    @Test
    void tooShort_reportsTheSizeKey() {
        assertThat(templatesFor("a".repeat(7))).containsExactly("{validation.password.size}");
    }

    @Test
    void moreThan72Characters_reportsTheSizeKey() {
        assertThat(templatesFor("a".repeat(73))).contains("{validation.password.size}");
    }

    @Test
    void within72Characters_butOver72Bytes_reportsTheBytesKey() {
        // "đ" = 2 byte UTF-8: 36 ký tự = 72 byte (hợp lệ), 37 ký tự = 74 byte (quá byte, vẫn ≤ 72 ký tự).
        assertThat(templatesFor("đ".repeat(36))).isEmpty();
        assertThat(templatesFor("đ".repeat(37))).containsExactly("{validation.password.too-many-bytes}");
    }

    @Test
    void ownMessageIsAnExistingKey() throws Exception {
        assertThat(ValidPassword.class.getMethod("message").getDefaultValue())
                .isEqualTo("{validation.password.size}");
    }
}
