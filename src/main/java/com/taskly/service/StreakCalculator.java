package com.taskly.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Streak mục tiêu ngày / tuần (M10 plan F3) — hàm THUẦN, không phụ thuộc Spring / DB.
 * Ngày: đạt (số hoàn thành ≥ mục tiêu) → streak + 1; không đạt mà là ngày bỏ qua (ngày nghỉ / Vacation) → giữ nguyên;
 * hôm nay chưa đạt → giữ nguyên (còn cả ngày để đạt); còn lại → về 0. Tuần tương tự, tuần bỏ qua = tuần có ngày Vacation.
 */
public final class StreakCalculator {

    /** current = streak đang chạy tính tới hôm nay; longest = dài nhất từng có. */
    public record Streak(int current, int longest) {
    }

    private StreakCalculator() {
    }

    /** counts: số hoàn thành theo ngày (múi giờ tài khoản); skip: ngày nghỉ / ngày trong kỳ Vacation. */
    public static Streak daily(Map<LocalDate, Integer> counts, LocalDate today, int goal, Predicate<LocalDate> skip) {
        LocalDate first = counts.isEmpty() ? today : Collections.min(counts.keySet());
        int run = 0;
        int longest = 0;
        for (LocalDate day = first; !day.isAfter(today); day = day.plusDays(1)) {
            if (counts.getOrDefault(day, 0) >= goal) {
                run++;
                longest = Math.max(longest, run);
            } else if (!day.equals(today) && !skip.test(day)) {
                run = 0;
            }
        }
        return new Streak(run, longest);
    }

    /** weekCounts: tổng theo ngày đầu tuần (sumByWeek); skipWeek: tuần có ngày Vacation. */
    public static Streak weekly(Map<LocalDate, Integer> counts, LocalDate thisWeekStart, int goal,
                                Predicate<LocalDate> skipWeek) {
        Map<LocalDate, Integer> weeks = sumByWeekStart(counts, thisWeekStart.getDayOfWeek());
        LocalDate first = weeks.isEmpty() ? thisWeekStart : Collections.min(weeks.keySet());
        int run = 0;
        int longest = 0;
        for (LocalDate week = first; !week.isAfter(thisWeekStart); week = week.plusWeeks(1)) {
            if (weeks.getOrDefault(week, 0) >= goal) {
                run++;
                longest = Math.max(longest, run);
            } else if (!week.equals(thisWeekStart) && !skipWeek.test(week)) {
                run = 0;
            }
        }
        return new Streak(run, longest);
    }

    /** Ngày đầu tuần chứa day; startDay = 1 (Thứ Hai) … 7 (Chủ nhật) như users.start_day. */
    public static LocalDate weekStart(LocalDate day, int startDay) {
        return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.of(startDay)));
    }

    /** Tổng số hoàn thành theo ngày đầu tuần. */
    public static Map<LocalDate, Integer> sumByWeek(Map<LocalDate, Integer> counts, int startDay) {
        return sumByWeekStart(counts, DayOfWeek.of(startDay));
    }

    private static Map<LocalDate, Integer> sumByWeekStart(Map<LocalDate, Integer> counts, DayOfWeek startDay) {
        Map<LocalDate, Integer> weeks = new HashMap<>();
        counts.forEach((day, count) ->
                weeks.merge(day.with(TemporalAdjusters.previousOrSame(startDay)), count, Integer::sum));
        return weeks;
    }
}
