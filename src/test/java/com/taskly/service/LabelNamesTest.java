package com.taskly.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ProblemTexts;
import org.junit.jupiter.api.Test;

class LabelNamesTest {

    private static void assertRejected(String raw, String detail) {
        assertThatThrownBy(() -> LabelNames.normalize(raw)).as(String.valueOf(raw))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getBody().getDetail()).isEqualTo(detail));
    }

    @Test
    void trimsEdgesAndNormalizesToNfc() {
        assertThat(LabelNames.normalize("  gấp ")).isEqualTo("gấp");
        assertThat(LabelNames.normalize("Ho\u0323p")).isEqualTo("H\u1ECDp");
        assertThat(LabelNames.normalize("x".repeat(60))).hasSize(60);
    }

    @Test
    void rejectsEmptyTooLongAndForbiddenCharacters() {
        assertRejected(null, ProblemTexts.LABEL_NAME_SIZE);
        assertRejected("   ", ProblemTexts.LABEL_NAME_SIZE);
        assertRejected("x".repeat(61), ProblemTexts.LABEL_NAME_SIZE);
        for (String bad : new String[] {"hai từ", "a\u00A0b", "a@b", "a%b", "a#b", "a/b", "a,b", "a(b", "a)b", "a|b",
                "a&b", "a!b", "a\\b", "a*b"}) {
            assertRejected(bad, ProblemTexts.LABEL_NAME_CHARS);
        }
    }
}
