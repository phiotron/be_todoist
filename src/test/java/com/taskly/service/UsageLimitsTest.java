package com.taskly.service;

import com.taskly.common.error.ErrorMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.common.error.ApiException;
import com.taskly.entity.LimitKind;
import org.junit.jupiter.api.Test;

class UsageLimitsTest {

    private final UsageLimits limits = new UsageLimits(300, 500, 300, 20, 100, 500, 150, 700);

    @Test
    void maxReadsConfiguredValues() {
        assertThat(limits.max(LimitKind.ACTIVE_PROJECTS)).isEqualTo(300);
        assertThat(limits.max(LimitKind.PROJECTS)).isEqualTo(500);
        assertThat(limits.max(LimitKind.PROJECT_ACTIVE_TASKS)).isEqualTo(300);
        assertThat(limits.max(LimitKind.PROJECT_SECTIONS)).isEqualTo(20);
        assertThat(limits.max(LimitKind.PROJECT_SECTIONS_TOTAL)).isEqualTo(100);
        assertThat(limits.max(LimitKind.LABELS)).isEqualTo(500);
        assertThat(limits.max(LimitKind.FILTERS)).isEqualTo(150);
        assertThat(limits.max(LimitKind.REMINDERS)).isEqualTo(700);
    }

    @Test
    void check_allowsUpToMax_thenThrows422WithCodeLimitAndMax() {
        assertThatCode(() -> limits.check(LimitKind.PROJECT_SECTIONS, 19, 1)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limits.check(LimitKind.PROJECT_SECTIONS, 20, 1))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(422);
                    assertThat(ex.getErrorMessage()).isEqualTo(ErrorMessage.LIMIT_REACHED);
                    assertThat(ex.getBody().getProperties())
                            .containsEntry("code", "limit-reached")
                            .containsEntry("limit", "PROJECT_SECTIONS")
                            .containsEntry("max", 20);
                });
        assertThatThrownBy(() -> limits.check(LimitKind.PROJECT_ACTIVE_TASKS, 295, 6)).isInstanceOf(ApiException.class);
    }

    @Test
    void addingNothing_neverThrows_evenWhenAlreadyOverTheLimit() {
        assertThatCode(() -> limits.check(LimitKind.ACTIVE_PROJECTS, 999, 0)).doesNotThrowAnyException();
    }

    @Test
    void overrideMax_isUsedUntilReset() {
        limits.overrideMax(LimitKind.ACTIVE_PROJECTS, 2);
        assertThat(limits.max(LimitKind.ACTIVE_PROJECTS)).isEqualTo(2);
        assertThatThrownBy(() -> limits.check(LimitKind.ACTIVE_PROJECTS, 2, 1)).isInstanceOf(ApiException.class);

        limits.resetOverrides();
        assertThat(limits.max(LimitKind.ACTIVE_PROJECTS)).isEqualTo(300);
    }
}
