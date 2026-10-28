package com.taskly.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.entity.Task;
import com.taskly.integration.calendar.EventDraft;
import com.taskly.integration.calendar.ExternalEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Task ↔ sự kiện (M13b): phần thuần, không Spring. */
class CalendarTaskEventsTest {

    private static final ZoneId HCM = ZoneId.of("Asia/Ho_Chi_Minh");

    private static Task task(LocalDate date, LocalDateTime datetime, Integer duration) {
        return new Task(UUID.randomUUID(), UUID.randomUUID(), null, null, UUID.randomUUID(), "Việc", "Mô tả", 4, date,
                datetime, duration, null, 1);
    }

    @Test
    void allDayTaskBecomesAnAllDayDraft() {
        EventDraft draft = CalendarTaskEvents.draftOf(task(LocalDate.of(2026, 11, 2), null, null), HCM, 60);

        assertThat(draft.allDay()).isTrue();
        assertThat(draft.date()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(draft.description()).startsWith("Mô tả").contains("taskly://task/");
    }

    @Test
    void timedTaskUsesTheUsersZoneAndDefaultOrOwnDuration() {
        LocalDateTime at = LocalDateTime.of(2026, 11, 2, 9, 0);

        EventDraft defaulted = CalendarTaskEvents.draftOf(task(null, at, null), HCM, 60);
        EventDraft own = CalendarTaskEvents.draftOf(task(null, at, 45), HCM, 60);

        assertThat(defaulted.start()).isEqualTo(Instant.parse("2026-11-02T02:00:00Z"));
        assertThat(Duration.between(defaulted.start(), defaulted.end())).isEqualTo(Duration.ofMinutes(60));
        assertThat(Duration.between(own.start(), own.end())).isEqualTo(Duration.ofMinutes(45));
    }

    @Test
    void fingerprintChangesWithAnyPushedField() {
        Task t = task(LocalDate.of(2026, 11, 2), null, null);
        String base = CalendarTaskEvents.fingerprint(CalendarTaskEvents.draftOf(t, HCM, 60));

        t.setContent("Đổi tên");
        assertThat(CalendarTaskEvents.fingerprint(CalendarTaskEvents.draftOf(t, HCM, 60))).isNotEqualTo(base);
        assertThat(CalendarTaskEvents.fingerprint(CalendarTaskEvents.draftOf(task(LocalDate.of(2026, 11, 2), null, null),
                HCM, 60))).hasSize(64);
    }

    @Test
    void pulledDueReadsMovedAllDayAndTimedEvents() {
        Task allDay = task(LocalDate.of(2026, 11, 2), null, null);
        assertThat(CalendarTaskEvents.pulledDue(ExternalEvent.allDay("e", "x", LocalDate.of(2026, 11, 2),
                LocalDate.of(2026, 11, 3)), allDay, HCM, 60)).isNull();
        var movedDay = CalendarTaskEvents.pulledDue(ExternalEvent.allDay("e", "x", LocalDate.of(2026, 11, 5),
                LocalDate.of(2026, 11, 6)), allDay, HCM, 60);
        assertThat(movedDay.dueDate()).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(movedDay.dueDatetime()).isNull();

        Task timed = task(null, LocalDateTime.of(2026, 11, 2, 9, 0), null);
        Instant same = Instant.parse("2026-11-02T02:00:00Z");
        assertThat(CalendarTaskEvents.pulledDue(ExternalEvent.timed("e", "x", same, same.plus(Duration.ofMinutes(60))),
                timed, HCM, 60)).isNull();
        var longer = CalendarTaskEvents.pulledDue(ExternalEvent.timed("e", "x", same, same.plus(Duration.ofMinutes(90))),
                timed, HCM, 60);
        assertThat(longer.dueDatetime()).isEqualTo(LocalDateTime.of(2026, 11, 2, 9, 0));
        assertThat(longer.durationMinutes()).isEqualTo(90);
        var moved = CalendarTaskEvents.pulledDue(ExternalEvent.timed("e", "x", same.plus(Duration.ofHours(2)),
                same.plus(Duration.ofHours(3))), timed, HCM, 60);
        assertThat(moved.dueDatetime()).isEqualTo(LocalDateTime.of(2026, 11, 2, 11, 0));
        assertThat(moved.durationMinutes()).isNull();
        assertThat(CalendarTaskEvents.pulledDue(ExternalEvent.cancelled("e"), timed, HCM, 60)).isNull();
    }
}
