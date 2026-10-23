package com.taskly.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MaxUtf8BytesTest {

    record Sample(@MaxUtf8Bytes(value = 4, message = "quá dài") String value) {
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

    @Test
    void acceptsAsciiUpToLimit() {
        assertThat(validator.validate(new Sample("abcd"))).isEmpty();
    }

    @Test
    void rejectsAsciiOverLimitWithMessage() {
        assertThat(validator.validate(new Sample("abcde")))
                .extracting(ConstraintViolation::getMessage)
                .containsExactly("quá dài");
    }

    @Test
    void countsVietnameseCharactersAsMultipleBytes() {
        // "ạạ" chỉ 2 ký tự nhưng 6 byte UTF-8 (mỗi "ạ" = 3 byte).
        assertThat(validator.validate(new Sample("ạạ"))).hasSize(1);
    }
}
