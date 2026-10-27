package com.taskly.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.common.error.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class TaskDueRulesTest {

    private static final ZoneId HCM = ZoneId.of("Asia/Ho_Chi_Minh"); // UTC+7
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    @Test
    void todayAndLocalNow_followUserTimeZoneAroundMidnight() {
        assertThat(TaskDueRules.today(Instant.parse("2026-10-23T16:59:59Z"), HCM)).isEqualTo(LocalDate.of(2026, 10, 23));
        assertThat(TaskDueRules.today(Instant.parse("2026-10-23T17:00:00Z"), HCM)).isEqualTo(LocalDate.of(2026, 10, 24));
        assertThat(TaskDueRules.localNow(Instant.parse("2026-10-23T16:59:00Z"), HCM))
                .isEqualTo(LocalDateTime.of(2026, 10, 23, 23, 59));
    }

    @Test
    void datetimeTask_isOverdueAsSoonAsTimePasses_evenToday() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 23, 12, 0);
        assertThat(TaskDueRules.isOverdue(null, LocalDateTime.of(2026, 10, 23, 11, 59), null, now)).isTrue();
        assertThat(TaskDueRules.isOverdue(null, LocalDateTime.of(2026, 10, 23, 12, 1), null, now)).isFalse();
    }

    @Test
    void dateOnlyTask_isOverdueOnlyFromNextDay() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 23, 23, 0);
        assertThat(TaskDueRules.isOverdue(LocalDate.of(2026, 10, 23), null, null, now)).isFalse();
        assertThat(TaskDueRules.isOverdue(LocalDate.of(2026, 10, 22), null, null, now)).isTrue();
    }

    @Test
    void completedOrUndatedTask_isNeverOverdue() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 23, 12, 0);
        assertThat(TaskDueRules.isOverdue(LocalDate.of(2026, 1, 1), null, Instant.now(), now)).isFalse();
        assertThat(TaskDueRules.isOverdue(null, null, null, now)).isFalse();
    }

    @Test
    void floatingDue_isComparedWithWallClockOfAccountZone() {
        // "9:00 ngày 25" là 9:00 ngày 25 ở bất kỳ múi giờ nào của tài khoản (spec 5.2).
        LocalDateTime nine = LocalDateTime.of(2026, 10, 25, 9, 0);
        Instant at = Instant.parse("2026-10-25T02:30:00Z"); // 09:30 ở HCM, 22:30 ngày 24 ở New York
        assertThat(TaskDueRules.isOverdue(null, nine, null, TaskDueRules.localNow(at, HCM))).isTrue();
        assertThat(TaskDueRules.isOverdue(null, nine, null, TaskDueRules.localNow(at, NEW_YORK))).isFalse();
    }

    @Test
    void normalize_truncatesToMinutes() {
        assertThat(TaskDueRules.normalize(LocalDateTime.of(2026, 10, 25, 9, 0, 59, 123))).isEqualTo(
                LocalDateTime.of(2026, 10, 25, 9, 0));
        assertThat(TaskDueRules.normalize(null)).isNull();
    }

    @Test
    void checkSingleDue_andCheckDuration() {
        LocalDateTime nine = LocalDateTime.of(2026, 10, 25, 9, 0);
        assertThatThrownBy(() -> TaskDueRules.checkSingleDue(LocalDate.of(2026, 10, 25), nine))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getBody().getDetail()).isEqualTo("Chỉ được đặt một trong dueDate hoặc dueDatetime"));
        assertThatThrownBy(() -> TaskDueRules.checkDuration(null, 30))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(400);
                    assertThat(ex.getBody().getDetail()).isEqualTo("Chỉ đặt được thời lượng cho công việc có giờ");
                });
        assertThatCode(() -> TaskDueRules.checkDuration(nine, 30)).doesNotThrowAnyException();
        assertThatCode(() -> TaskDueRules.checkDuration(null, null)).doesNotThrowAnyException();
    }

    @Test
    void dueDay_isTheDateOfTheDueOrNull() {
        assertThat(TaskDueRules.dueDay(LocalDate.of(2026, 10, 25), null)).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(TaskDueRules.dueDay(null, LocalDateTime.of(2026, 10, 26, 23, 30))).isEqualTo(LocalDate.of(2026, 10, 26));
        assertThat(TaskDueRules.dueDay(null, null)).isNull();
    }

    @Test
    void overdueBlock_isDueDayBeforeToday_aPastTimeTodayIsOnlyRed() {
        LocalDate today = LocalDate.of(2026, 10, 23);
        LocalDateTime now = LocalDateTime.of(2026, 10, 23, 12, 0);
        LocalDateTime earlierToday = LocalDateTime.of(2026, 10, 23, 9, 0);
        assertThat(TaskDueRules.isOverdue(null, earlierToday, null, now)).isTrue();                 // tô đỏ
        assertThat(TaskDueRules.isInOverdueBlock(null, earlierToday, null, today)).isFalse();       // nhưng ở khối Hôm nay
        assertThat(TaskDueRules.isInOverdueBlock(LocalDate.of(2026, 10, 22), null, null, today)).isTrue();
        assertThat(TaskDueRules.isInOverdueBlock(null, LocalDateTime.of(2026, 10, 22, 23, 59), null, today)).isTrue();
        assertThat(TaskDueRules.isInOverdueBlock(LocalDate.of(2026, 10, 23), null, null, today)).isFalse();
        assertThat(TaskDueRules.isInOverdueBlock(LocalDate.of(2026, 10, 1), null, Instant.now(), today)).isFalse(); // đã xong
        assertThat(TaskDueRules.isInOverdueBlock(null, null, null, today)).isFalse();
    }

    @Test
    void reschedule_keepsTimeOfDatetimeTask_andNullClearsDue() {
        LocalDate newDate = LocalDate.of(2026, 10, 27);
        assertThat(TaskDueRules.reschedule(null, LocalDateTime.of(2026, 10, 20, 9, 15), newDate))
                .isEqualTo(new TaskDueRules.Due(null, LocalDateTime.of(2026, 10, 27, 9, 15)));
        assertThat(TaskDueRules.reschedule(LocalDate.of(2026, 10, 20), null, newDate))
                .isEqualTo(new TaskDueRules.Due(newDate, null));
        assertThat(TaskDueRules.reschedule(null, null, newDate)).isEqualTo(new TaskDueRules.Due(newDate, null));
        assertThat(TaskDueRules.reschedule(LocalDate.of(2026, 10, 20), null, null))
                .isEqualTo(new TaskDueRules.Due(null, null));
    }
}
