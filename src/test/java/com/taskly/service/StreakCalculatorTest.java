package com.taskly.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** Streak ngày / tuần (M10 plan F3) — hàm thuần, "hôm nay" cố định. */
class StreakCalculatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12); // Thứ Năm
    private static final Predicate<LocalDate> NO_SKIP = day -> false;

    private static Map<LocalDate, Integer> counts(Object... dayAndCount) {
        Map<LocalDate, Integer> map = new HashMap<>();
        for (int i = 0; i < dayAndCount.length; i += 2) {
            map.put(TODAY.minusDays((Integer) dayAndCount[i]), (Integer) dayAndCount[i + 1]);
        }
        return map;
    }

    @Test
    void daily_noData_isZero() {
        assertThat(StreakCalculator.daily(Map.of(), TODAY, 5, NO_SKIP))
                .isEqualTo(new StreakCalculator.Streak(0, 0));
    }

    @Test
    void daily_countsConsecutiveReachedDaysIncludingToday() {
        // hôm nay, hôm qua, hôm kia đạt; 3 ngày trước không đạt
        Map<LocalDate, Integer> counts = counts(0, 5, 1, 6, 2, 5, 3, 4);
        assertThat(StreakCalculator.daily(counts, TODAY, 5, NO_SKIP))
                .isEqualTo(new StreakCalculator.Streak(3, 3));
    }

    @Test
    void daily_todayNotReachedYet_keepsStreakUpToYesterday() {
        Map<LocalDate, Integer> counts = counts(0, 2, 1, 5, 2, 5);
        assertThat(StreakCalculator.daily(counts, TODAY, 5, NO_SKIP).current()).isEqualTo(2);
    }

    @Test
    void daily_missedYesterday_breaksStreak() {
        Map<LocalDate, Integer> counts = counts(0, 1, 2, 5, 3, 5, 4, 5);
        assertThat(StreakCalculator.daily(counts, TODAY, 5, NO_SKIP))
                .isEqualTo(new StreakCalculator.Streak(0, 3));
    }

    @Test
    void daily_daysOffAndVacationDaysAreSkipped() {
        // 1 ngày trước = ngày nghỉ (không đạt), 2 ngày trước đạt, 3 ngày trước = vacation, 4 ngày trước đạt
        Set<LocalDate> skipped = Set.of(TODAY.minusDays(1), TODAY.minusDays(3));
        Map<LocalDate, Integer> counts = counts(0, 5, 2, 5, 4, 5);
        assertThat(StreakCalculator.daily(counts, TODAY, 5, skipped::contains))
                .isEqualTo(new StreakCalculator.Streak(3, 3));
    }

    @Test
    void daily_reachedOnDayOff_stillCounts() {
        Set<LocalDate> skipped = Set.of(TODAY.minusDays(1));
        Map<LocalDate, Integer> counts = counts(0, 5, 1, 5);
        assertThat(StreakCalculator.daily(counts, TODAY, 5, skipped::contains).current()).isEqualTo(2);
    }

    @Test
    void daily_longestStreakIsKeptAfterABreak() {
        Map<LocalDate, Integer> counts = counts(0, 5, 3, 5, 4, 5, 5, 5, 6, 5);
        assertThat(StreakCalculator.daily(counts, TODAY, 5, NO_SKIP))
                .isEqualTo(new StreakCalculator.Streak(1, 4));
    }

    @Test
    void weekStart_followsAccountStartDay() {
        assertThat(StreakCalculator.weekStart(TODAY, 1)).isEqualTo(LocalDate.of(2026, 11, 9)); // Thứ Hai
        assertThat(StreakCalculator.weekStart(TODAY, 7)).isEqualTo(LocalDate.of(2026, 11, 8)); // Chủ nhật
        assertThat(StreakCalculator.weekStart(TODAY, 4)).isEqualTo(TODAY); // Thứ Năm
        assertThat(StreakCalculator.weekStart(TODAY, 5)).isEqualTo(LocalDate.of(2026, 11, 6)); // Thứ Sáu
        assertThat(StreakCalculator.weekStart(TODAY, 1).getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
    }

    @Test
    void weekly_sumsWeeksAndSkipsCurrentWeekUntilReached() {
        LocalDate thisWeek = StreakCalculator.weekStart(TODAY, 1);
        Map<LocalDate, Integer> counts = new HashMap<>();
        counts.put(thisWeek, 3);                    // tuần này: 3 (chưa đạt 30)
        counts.put(thisWeek.minusDays(7), 20);      // tuần trước: 20 + 10 = 30 → đạt
        counts.put(thisWeek.minusDays(5), 10);
        counts.put(thisWeek.minusDays(14), 31);     // 2 tuần trước: đạt
        counts.put(thisWeek.minusDays(21), 5);      // 3 tuần trước: không đạt

        assertThat(StreakCalculator.weekly(counts, thisWeek, 30, week -> false))
                .isEqualTo(new StreakCalculator.Streak(2, 2));
    }

    @Test
    void weekly_vacationWeekIsSkipped() {
        LocalDate thisWeek = StreakCalculator.weekStart(TODAY, 1);
        Map<LocalDate, Integer> counts = new HashMap<>();
        counts.put(thisWeek, 30);
        counts.put(thisWeek.minusDays(14), 30); // tuần trước = vacation, không đạt
        LocalDate vacationWeek = thisWeek.minusDays(7);

        assertThat(StreakCalculator.weekly(counts, thisWeek, 30, vacationWeek::equals).current()).isEqualTo(2);
    }

    @Test
    void sumByWeek_groupsDaysIntoWeekStarts() {
        LocalDate thisWeek = StreakCalculator.weekStart(TODAY, 1);
        Map<LocalDate, Integer> counts = counts(0, 2, 1, 3, 4, 1); // Th5, Th4 tuần này; Chủ nhật tuần trước
        assertThat(StreakCalculator.sumByWeek(counts, 1))
                .containsEntry(thisWeek, 5)
                .containsEntry(thisWeek.minusDays(7), 1);
    }
}
