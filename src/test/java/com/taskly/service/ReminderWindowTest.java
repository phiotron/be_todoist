package com.taskly.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ReminderWindowTest {

    @ParameterizedTest(name = "{0} ở {1} → {2}")
    @CsvSource({
            // Asia/Ho_Chi_Minh = UTC+7: 16:59:59Z vẫn là 23:59:59 ngày 23, 17:00:00Z đã là 00:00 ngày 24
            "2026-10-23T16:59:59Z, Asia/Ho_Chi_Minh, 2026-10-23T23:59:59",
            "2026-10-23T17:00:00Z, Asia/Ho_Chi_Minh, 2026-10-24T00:00",
            // America/New_York tháng 10/2026 = UTC-4 (giờ mùa hè tới 01/11)
            "2026-10-23T03:59:59Z, America/New_York, 2026-10-22T23:59:59",
            "2026-10-23T04:00:00Z, America/New_York, 2026-10-23T00:00",
            // Pacific/Kiritimati = UTC+14: sớm hơn UTC gần một ngày
            "2026-10-23T10:00:00Z, Pacific/Kiritimati, 2026-10-24T00:00",
            "2026-10-23T00:00:00Z, UTC, 2026-10-23T00:00"
    })
    void nowIsTheWallClockOfTheAccountTimeZone(String instant, String zone, String expected) {
        ReminderWindow window = ReminderWindow.at(Instant.parse(instant), ZoneId.of(zone));

        assertThat(window.now()).isEqualTo(LocalDateTime.parse(expected));
    }

    @Test
    void timeEqualToNowHasNotPassed_earlierHas() {
        ReminderWindow window = new ReminderWindow(LocalDateTime.parse("2026-10-23T10:00:00"));

        assertThat(window.hasPassed(LocalDateTime.parse("2026-10-23T10:00:00"))).isFalse();
        assertThat(window.hasPassed(LocalDateTime.parse("2026-10-23T09:59:59"))).isTrue();
        assertThat(window.hasPassed(LocalDateTime.parse("2026-10-23T10:01:00"))).isFalse();
    }

    @Test
    void toMinuteDropsSecondsAndFractions() {
        assertThat(ReminderWindow.toMinute(LocalDateTime.parse("2026-10-23T11:30:45.123456")))
                .isEqualTo(LocalDateTime.parse("2026-10-23T11:30"));
    }

    @Test
    void limitDefaultsToFifty() {
        assertThat(ReminderWindow.limit(null)).isEqualTo(50);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 25, 50})
    void limitAcceptsOneToFifty(int limit) {
        assertThat(ReminderWindow.limit(limit)).isEqualTo(limit);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 51, 200})
    void limitOutsideOneToFiftyIsBadRequest(int limit) {
        assertThatThrownBy(() -> ReminderWindow.limit(limit))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(400);
                    assertThat(ex.getErrorMessage()).isEqualTo(ErrorMessage.REMINDER_LIMIT_RANGE);
                });
    }

    @Test
    void keyIsAutoPrefixedTaskIdForAutoReminders_andReminderIdOtherwise() {
        UUID taskId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID reminderId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        assertThat(ReminderWindow.key(taskId, null)).isEqualTo("auto:11111111-1111-1111-1111-111111111111");
        assertThat(ReminderWindow.key(taskId, reminderId)).isEqualTo("22222222-2222-2222-2222-222222222222");
    }
}
