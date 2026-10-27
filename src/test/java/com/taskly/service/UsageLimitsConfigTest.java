package com.taskly.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.IntegrationTestBase;
import com.taskly.entity.LimitKind;
import org.junit.jupiter.api.Test;

/** Bean đọc đúng taskly.limits.* của application.yml (spec 2.1) và IntegrationTestBase gỡ override trước mỗi test. */
class UsageLimitsConfigTest extends IntegrationTestBase {

    @Test
    void beanUsesProLimitsFromApplicationYml() {
        assertThat(usageLimits.max(LimitKind.ACTIVE_PROJECTS)).isEqualTo(300);
        assertThat(usageLimits.max(LimitKind.PROJECTS)).isEqualTo(500);
        assertThat(usageLimits.max(LimitKind.PROJECT_ACTIVE_TASKS)).isEqualTo(300);
        assertThat(usageLimits.max(LimitKind.PROJECT_SECTIONS)).isEqualTo(20);
        assertThat(usageLimits.max(LimitKind.PROJECT_SECTIONS_TOTAL)).isEqualTo(100);
        assertThat(usageLimits.max(LimitKind.LABELS)).isEqualTo(500);
        assertThat(usageLimits.max(LimitKind.FILTERS)).isEqualTo(150);
        assertThat(usageLimits.max(LimitKind.REMINDERS)).isEqualTo(700);
    }

    @Test
    void baseClassHook_clearsOverrides() {
        usageLimits.overrideMax(LimitKind.PROJECT_SECTIONS, 1);
        assertThat(usageLimits.max(LimitKind.PROJECT_SECTIONS)).isEqualTo(1);
        resetUsageLimits(); // @BeforeEach của IntegrationTestBase
        assertThat(usageLimits.max(LimitKind.PROJECT_SECTIONS)).isEqualTo(20);
    }
}
