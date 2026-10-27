package com.taskly.service;

import com.taskly.common.error.ErrorMessage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.common.error.ApiException;

import org.junit.jupiter.api.Test;

class LabelNamesTest {

    private static void assertRejected(String raw, ErrorMessage detail) {
        assertThatThrownBy(() -> LabelNames.normalize(raw)).as(String.valueOf(raw))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getErrorMessage()).isEqualTo(detail));
    }

    @Test
    void trimsEdgesAndNormalizesToNfc() {
        assertThat(LabelNames.normalize("  gấp ")).isEqualTo("gấp");
        assertThat(LabelNames.normalize("Ho\u0323p")).isEqualTo("H\u1ECDp");
        assertThat(LabelNames.normalize("x".repeat(60))).hasSize(60);
    }

    @Test
    void rejectsEmptyTooLongAndForbiddenCharacters() {
        assertRejected(null, ErrorMessage.LABEL_NAME_SIZE);
        assertRejected("   ", ErrorMessage.LABEL_NAME_SIZE);
        assertRejected("x".repeat(61), ErrorMessage.LABEL_NAME_SIZE);
        for (String bad : new String[] {"hai từ", "a\u00A0b", "a@b", "a%b", "a#b", "a/b", "a,b", "a(b", "a)b", "a|b",
                "a&b", "a!b", "a\\b", "a*b"}) {
            assertRejected(bad, ErrorMessage.LABEL_NAME_CHARS);
        }
    }
}
