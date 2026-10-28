package com.taskly.service.recurrence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** RRULE rút gọn (contract 8.1): tập con hợp lệ, dạng chuẩn, neo theo due. */
class RecurrenceRuleTest {

    @Test
    void parse_readsEveryPartOfTheSubset() {
        RecurrenceRule rule = RecurrenceRule.parse("FREQ=MONTHLY;INTERVAL=2;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1;UNTIL=20271231");

        assertThat(rule.freq()).isEqualTo(RecurrenceRule.Freq.MONTHLY);
        assertThat(rule.interval()).isEqualTo(2);
        assertThat(rule.byDay()).extracting(RecurrenceRule.WeekdayNum::weekday)
                .containsExactly(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
                        DayOfWeek.FRIDAY);
        assertThat(rule.bySetPos()).isEqualTo(-1);
        assertThat(rule.until()).isEqualTo(LocalDate.of(2027, 12, 31));
    }

    @Test
    void parse_ordinalWeekdaysAndMonthDays() {
        RecurrenceRule rule = RecurrenceRule.parse("FREQ=YEARLY;BYMONTH=11;BYDAY=4TH");
        assertThat(rule.byMonth()).containsExactly(11);
        assertThat(rule.byDay()).containsExactly(new RecurrenceRule.WeekdayNum(DayOfWeek.THURSDAY, 4));

        assertThat(RecurrenceRule.parse("FREQ=MONTHLY;BYDAY=-1FR").byDay())
                .containsExactly(new RecurrenceRule.WeekdayNum(DayOfWeek.FRIDAY, -1));
        assertThat(RecurrenceRule.parse("FREQ=MONTHLY;BYMONTHDAY=-1").byMonthDay()).containsExactly(-1);
        assertThat(RecurrenceRule.parse("FREQ=MONTHLY;BYMONTHDAY=1,15").byMonthDay()).containsExactly(1, 15);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "RRULE:FREQ=DAILY",
            "FREQ=HOURLY",
            "FREQ=DAILY;COUNT=3",
            "FREQ=DAILY;BYHOUR=9",
            "FREQ=DAILY;BYMINUTE=30",
            "FREQ=DAILY;WKST=MO",
            "INTERVAL=2",
            "FREQ=DAILY;INTERVAL=0",
            "FREQ=DAILY;INTERVAL=1000",
            "FREQ=DAILY;INTERVAL=abc",
            "FREQ=DAILY;INTERVAL=+2",
            "FREQ=DAILY;FREQ=WEEKLY",
            "FREQ=WEEKLY;BYDAY=XX",
            "FREQ=WEEKLY;BYDAY=",
            "FREQ=WEEKLY;BYDAY=1MO",
            "FREQ=MONTHLY;BYDAY=5MO",
            "FREQ=MONTHLY;BYDAY=-2MO",
            "FREQ=MONTHLY;BYDAY=0MO",
            "FREQ=MONTHLY;BYMONTHDAY=0",
            "FREQ=MONTHLY;BYMONTHDAY=32",
            "FREQ=MONTHLY;BYMONTHDAY=-2",
            "FREQ=WEEKLY;BYMONTHDAY=3",
            "FREQ=YEARLY;BYMONTH=13",
            "FREQ=YEARLY;BYMONTH=0",
            "FREQ=MONTHLY;BYSETPOS=-1",
            "FREQ=MONTHLY;BYDAY=MO,FR;BYSETPOS=5",
            "FREQ=MONTHLY;BYDAY=MO,FR;BYSETPOS=-2",
            "FREQ=DAILY;UNTIL=2026-10-29",
            "FREQ=DAILY;UNTIL=20261332",
            "freq=daily",
            "FREQ=DAILY;",
            "FREQ=DAILY;;INTERVAL=2",
            "FREQ = DAILY"})
    void parse_rejectsAnythingOutsideTheSubset(String rule) {
        assertThatThrownBy(() -> RecurrenceRule.parse(rule)).isInstanceOf(InvalidRecurrenceException.class);
    }

    @Test
    void parse_rejectsNullAndTooLong() {
        assertThatThrownBy(() -> RecurrenceRule.parse(null)).isInstanceOf(InvalidRecurrenceException.class);
        String tooLong = "FREQ=MONTHLY;BYMONTHDAY=" + String.join(",", java.util.Collections.nCopies(300, "1"));
        assertThatThrownBy(() -> RecurrenceRule.parse(tooLong)).isInstanceOf(InvalidRecurrenceException.class);
    }

    @Test
    void format_isCanonical_andDropsIntervalOne() {
        assertThat(RecurrenceRule.parse("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO").format()).isEqualTo("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO");
        assertThat(RecurrenceRule.parse("FREQ=DAILY;INTERVAL=1").format()).isEqualTo("FREQ=DAILY");
        assertThat(RecurrenceRule.parse("UNTIL=20261231;BYMONTHDAY=29;BYMONTH=2;FREQ=YEARLY").format())
                .isEqualTo("FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=29;UNTIL=20261231");
        assertThat(RecurrenceRule.parse("FREQ=MONTHLY;BYSETPOS=-1;BYDAY=MO,TU,WE,TH,FR").format())
                .isEqualTo("FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1");
        assertThat(RecurrenceRule.parse("FREQ=MONTHLY;BYDAY=3FR,-1MO").format()).isEqualTo("FREQ=MONTHLY;BYDAY=3FR,-1MO");
    }

    @Test
    void anchoredTo_addsTheMissingAnchorFromTheDueDay() {
        LocalDate wednesday31 = LocalDate.of(2026, 12, 31); // Thứ Năm
        assertThat(RecurrenceRule.parse("FREQ=WEEKLY;INTERVAL=2").anchoredTo(wednesday31).format())
                .isEqualTo("FREQ=WEEKLY;INTERVAL=2;BYDAY=TH");
        assertThat(RecurrenceRule.parse("FREQ=MONTHLY").anchoredTo(wednesday31).format())
                .isEqualTo("FREQ=MONTHLY;BYMONTHDAY=31");
        assertThat(RecurrenceRule.parse("FREQ=YEARLY").anchoredTo(LocalDate.of(2028, 2, 29)).format())
                .isEqualTo("FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=29");
        // đã có neo / DAILY → giữ nguyên
        for (String kept : List.of("FREQ=DAILY;INTERVAL=3", "FREQ=WEEKLY;BYDAY=MO", "FREQ=MONTHLY;BYDAY=3FR",
                "FREQ=MONTHLY;BYMONTHDAY=-1", "FREQ=YEARLY;BYMONTH=11;BYDAY=4TH", "FREQ=YEARLY;BYMONTH=3")) {
            assertThat(RecurrenceRule.parse(kept).anchoredTo(wednesday31).format()).isEqualTo(kept);
        }
    }

    @Test
    void hasNoByParts_onlyForPlainIntervals() {
        assertThat(RecurrenceRule.parse("FREQ=MONTHLY;INTERVAL=3;UNTIL=20271231").hasByParts()).isFalse();
        assertThat(RecurrenceRule.parse("FREQ=MONTHLY;BYMONTHDAY=3").hasByParts()).isTrue();
        assertThat(RecurrenceRule.parse("FREQ=YEARLY;BYMONTH=3").hasByParts()).isTrue();
    }
}
