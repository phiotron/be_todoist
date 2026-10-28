package com.taskly.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit thuần: danh sách chip Quick Add của me/settings (contract M6b mục 2, 8.2). */
class QuickAddChipsTest {

    @Test
    void defaultIsProjectDateAssignee() {
        assertThat(QuickAddChipRules.DEFAULT).containsExactly("PROJECT", "DATE", "ASSIGNEE");
        assertThat(QuickAddChipRules.isValid(QuickAddChipRules.DEFAULT)).isTrue();
    }

    @Test
    void acceptsAnyOrderOfKnownChipsThatKeepsProject() {
        assertThat(QuickAddChipRules.isValid(List.of("PROJECT"))).isTrue();
        assertThat(QuickAddChipRules.isValid(List.of("LABELS", "PROJECT", "PRIORITY"))).isTrue();
        assertThat(QuickAddChipRules.isValid(QuickAddChipRules.ALL)).isTrue();
        assertThat(QuickAddChipRules.ALL).hasSize(9);
    }

    @Test
    void rejectsMissingProjectDuplicatesUnknownOrMiscasedChips() {
        assertThat(QuickAddChipRules.isValid(List.of())).isFalse();
        assertThat(QuickAddChipRules.isValid(List.of("DATE", "PRIORITY"))).isFalse();
        assertThat(QuickAddChipRules.isValid(List.of("PROJECT", "DATE", "DATE"))).isFalse();
        assertThat(QuickAddChipRules.isValid(List.of("PROJECT", "COMMENTS"))).isFalse();
        assertThat(QuickAddChipRules.isValid(List.of("project", "DATE"))).isFalse();
        assertThat(QuickAddChipRules.isValid(Arrays.asList("PROJECT", null))).isFalse();
    }
}
