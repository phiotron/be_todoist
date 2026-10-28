package com.taskly.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Luật danh sách mục Navigation menu (M3x): 6 khoá cố định, không trùng, 0–6 phần tử, null = giữ nguyên. */
class NavMenuItemsValidatorTest {

    private final NavMenuItemsValidator validator = new NavMenuItemsValidator();

    @Test
    void nullAndEmptyAreValid() {
        assertThat(validator.isValid(null, null)).isTrue();
        assertThat(validator.isValid(List.of(), null)).isTrue();
    }

    @Test
    void anyOrderOfKnownKeysIsValid() {
        assertThat(validator.isValid(NavMenuItemsValidator.ALL_ITEMS, null)).isTrue();
        assertThat(validator.isValid(List.of("BROWSE_TEMPLATES", "INBOX"), null)).isTrue();
    }

    @Test
    void unknownDuplicateOrNullKeysAreInvalid() {
        assertThat(validator.isValid(List.of("INBOX", "INBOX"), null)).isFalse();
        assertThat(validator.isValid(List.of("BROWSE"), null)).isFalse();
        assertThat(validator.isValid(List.of("today"), null)).isFalse();
        assertThat(validator.isValid(Arrays.asList("INBOX", null), null)).isFalse();
    }

    @Test
    void defaultListHasTheSixTodoistItemsInOrder() {
        assertThat(NavMenuItemsValidator.ALL_ITEMS).containsExactly(
                "INBOX", "TODAY", "UPCOMING", "FILTERS_LABELS", "REPORTING", "BROWSE_TEMPLATES");
    }
}
