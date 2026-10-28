package com.taskly.service.recurrence;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Tính KỲ KẾ của task lặp (spec 5.2, 5.3; contract 8.1, 8.3) — THUẦN, không Spring, không DB. Mobile
 * lib/recurrence/next.ts chạy CÙNG thuật toán và cùng bộ vector (recurrence-vectors.json): sửa một bên phải sửa cả hai.
 *
 * <p>"Hôm nay" = ngày của thời điểm hoàn thành theo múi giờ TÀI KHOẢN (không phải múi giờ của máy / due_timezone).
 * <ul>
 * <li>`every` (fromCompletion = false): kỳ đầu tiên SAU max(due, hôm nay); chu kỳ căn theo due (kỳ k = kỳ chứa due + k ×
 * INTERVAL) → "cách tuần" giữ đúng nhịp, task trễ nhiều kỳ nhảy một lần tới kỳ đầu tiên sau hôm nay.</li>
 * <li>`every!` (fromCompletion = true): rule không BY* → hôm nay + INTERVAL đơn vị (tháng / năm thiếu ngày → ngày cuối);
 * có BY* → kỳ đầu tiên sau hôm nay, chu kỳ căn theo hôm nay.</li>
 * </ul>
 * Ứng viên trong một kỳ: BYMONTHDAY (31 ở tháng thiếu ngày → ngày cuối; -1 = ngày cuối), BYDAY (thứ tự 1–4 / -1 trong
 * tháng), giao nhau khi có cả hai, lọc BYMONTH, rồi BYSETPOS. Thiếu neo → lấy thứ / ngày / tháng của due. Giờ của due giữ
 * nguyên. Kỳ kế sau UNTIL → rỗng (hết chu kỳ).
 */
public class RecurrenceService {

    /** Số kỳ tối đa duyệt sau kỳ chứa ngưỡng — rule không còn ngày nào thì coi như hết chu kỳ thay vì lặp vô hạn. */
    static final int MAX_PERIODS = 5_000;

    /** Due của task: ngày + giờ (null = cả ngày). */
    public record Due(LocalDate date, LocalTime time) {

        private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

        public static Due of(LocalDate dueDate, LocalDateTime dueDatetime) {
            return dueDatetime != null ? new Due(dueDatetime.toLocalDate(), dueDatetime.toLocalTime()) : new Due(dueDate, null);
        }

        /** "yyyy-MM-dd" hoặc "yyyy-MM-ddTHH:mm[:ss]" (giờ floating). */
        public static Due parse(String text) {
            if (text.contains("T")) {
                LocalDateTime value = LocalDateTime.parse(text);
                return new Due(value.toLocalDate(), value.toLocalTime());
            }
            return new Due(LocalDate.parse(text), null);
        }

        public LocalDateTime toDatetime() {
            return time == null ? null : date.atTime(time);
        }

        public String format() {
            return time == null ? date.toString() : DATETIME.format(toDatetime());
        }
    }

    /** Kỳ kế; rỗng = hết chu kỳ (vượt UNTIL). */
    public Optional<Due> next(RecurrenceRule rule, boolean fromCompletion, Due due, Instant completedAt, ZoneId zone) {
        LocalDate today = LocalDate.ofInstant(completedAt, zone);
        LocalDate next;
        if (fromCompletion && !rule.hasByParts()) {
            next = plus(today, rule.freq(), rule.interval());
        } else if (fromCompletion) {
            next = firstAfter(rule, today, today);
        } else {
            next = firstAfter(rule, due.date(), due.date().isAfter(today) ? due.date() : today);
        }
        if (next == null || (rule.until() != null && next.isAfter(rule.until()))) {
            return Optional.empty();
        }
        return Optional.of(new Due(next, due.time()));
    }

    static LocalDate plus(LocalDate day, RecurrenceRule.Freq freq, int interval) {
        return switch (freq) {
            case DAILY -> day.plusDays(interval);
            case WEEKLY -> day.plusWeeks(interval);
            case MONTHLY -> day.plusMonths(interval); // 31/1 + 1 tháng = 28/2 (kẹp cuối tháng)
            case YEARLY -> day.plusYears(interval);   // 29/2 + 1 năm = 28/2
        };
    }

    /** Ứng viên đầu tiên > threshold, duyệt kỳ k = kỳ của anchor + k × INTERVAL (bỏ qua các kỳ nằm trọn trước ngưỡng). */
    static LocalDate firstAfter(RecurrenceRule rule, LocalDate anchor, LocalDate threshold) {
        LocalDate first = periodStart(rule.freq(), anchor);
        long skip = Math.max(0, units(rule.freq(), first, periodStart(rule.freq(), threshold)) / rule.interval());
        for (long k = skip; k < skip + MAX_PERIODS; k++) {
            LocalDate start = shift(rule.freq(), first, k * rule.interval());
            if (rule.until() != null && start.isAfter(rule.until())) {
                return null;
            }
            for (LocalDate candidate : candidates(rule, start, anchor)) {
                if (candidate.isAfter(threshold)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static LocalDate periodStart(RecurrenceRule.Freq freq, LocalDate day) {
        return switch (freq) {
            case DAILY -> day;
            case WEEKLY -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); // WKST = MO
            case MONTHLY -> day.withDayOfMonth(1);
            case YEARLY -> day.withDayOfYear(1);
        };
    }

    private static long units(RecurrenceRule.Freq freq, LocalDate from, LocalDate to) {
        return switch (freq) {
            case DAILY -> ChronoUnit.DAYS.between(from, to);
            case WEEKLY -> ChronoUnit.WEEKS.between(from, to);
            case MONTHLY -> ChronoUnit.MONTHS.between(from, to);
            case YEARLY -> ChronoUnit.YEARS.between(from, to);
        };
    }

    private static LocalDate shift(RecurrenceRule.Freq freq, LocalDate start, long amount) {
        return switch (freq) {
            case DAILY -> start.plusDays(amount);
            case WEEKLY -> start.plusWeeks(amount);
            case MONTHLY -> start.plusMonths(amount);
            case YEARLY -> start.plusYears(amount);
        };
    }

    /** Ứng viên đã sắp tăng dần, không trùng, của kỳ bắt đầu ở start. */
    static List<LocalDate> candidates(RecurrenceRule rule, LocalDate start, LocalDate anchor) {
        TreeSet<LocalDate> set = new TreeSet<>();
        switch (rule.freq()) {
            case DAILY -> {
                if (matchesDay(rule, start)) {
                    set.add(start);
                }
            }
            case WEEKLY -> {
                List<DayOfWeek> days = rule.byDay().isEmpty() ? List.of(anchor.getDayOfWeek())
                        : rule.byDay().stream().map(RecurrenceRule.WeekdayNum::weekday).toList();
                for (DayOfWeek day : days) {
                    LocalDate date = start.plusDays(day.getValue() - 1L);
                    if (rule.byMonth().isEmpty() || rule.byMonth().contains(date.getMonthValue())) {
                        set.add(date);
                    }
                }
            }
            case MONTHLY -> {
                if (rule.byMonth().isEmpty() || rule.byMonth().contains(start.getMonthValue())) {
                    set.addAll(monthCandidates(rule, YearMonth.from(start), anchor));
                }
            }
            case YEARLY -> {
                List<Integer> months = rule.byMonth().isEmpty() ? List.of(anchor.getMonthValue()) : rule.byMonth();
                for (int month : months) {
                    set.addAll(monthCandidates(rule, YearMonth.of(start.getYear(), month), anchor));
                }
            }
        }
        return bySetPos(new ArrayList<>(set), rule.bySetPos());
    }

    private static boolean matchesDay(RecurrenceRule rule, LocalDate date) {
        if (!rule.byMonth().isEmpty() && !rule.byMonth().contains(date.getMonthValue())) {
            return false;
        }
        if (!rule.byMonthDay().isEmpty()
                && !monthDays(rule.byMonthDay(), YearMonth.from(date)).contains(date)) {
            return false;
        }
        return rule.byDay().isEmpty()
                || rule.byDay().stream().anyMatch(d -> d.weekday() == date.getDayOfWeek());
    }

    private static TreeSet<LocalDate> monthCandidates(RecurrenceRule rule, YearMonth month, LocalDate anchor) {
        TreeSet<LocalDate> fromDays = rule.byMonthDay().isEmpty() ? null : monthDays(rule.byMonthDay(), month);
        TreeSet<LocalDate> fromWeekdays = rule.byDay().isEmpty() ? null : weekdays(rule.byDay(), month);
        if (fromDays == null && fromWeekdays == null) {
            TreeSet<LocalDate> single = new TreeSet<>();
            single.add(clamp(month, anchor.getDayOfMonth()));
            return single;
        }
        if (fromDays == null) {
            return fromWeekdays;
        }
        if (fromWeekdays != null) {
            fromDays.retainAll(fromWeekdays);
        }
        return fromDays;
    }

    /** BYMONTHDAY: -1 = ngày cuối; ngày vượt độ dài tháng → ngày cuối tháng (spec 5.2 "ngày 31 hằng tháng"). */
    private static TreeSet<LocalDate> monthDays(List<Integer> days, YearMonth month) {
        TreeSet<LocalDate> set = new TreeSet<>();
        for (int day : days) {
            set.add(day == -1 ? month.atEndOfMonth() : clamp(month, day));
        }
        return set;
    }

    private static TreeSet<LocalDate> weekdays(List<RecurrenceRule.WeekdayNum> byDay, YearMonth month) {
        TreeSet<LocalDate> set = new TreeSet<>();
        for (RecurrenceRule.WeekdayNum item : byDay) {
            LocalDate first = month.atDay(1).with(TemporalAdjusters.nextOrSame(item.weekday()));
            if (item.ordinal() == null) {
                for (LocalDate d = first; YearMonth.from(d).equals(month); d = d.plusWeeks(1)) {
                    set.add(d);
                }
            } else if (item.ordinal() == -1) {
                set.add(month.atEndOfMonth().with(TemporalAdjusters.previousOrSame(item.weekday())));
            } else {
                LocalDate nth = first.plusWeeks(item.ordinal() - 1L);
                if (YearMonth.from(nth).equals(month)) {
                    set.add(nth);
                }
            }
        }
        return set;
    }

    private static LocalDate clamp(YearMonth month, int day) {
        return month.atDay(Math.min(day, month.lengthOfMonth()));
    }

    private static List<LocalDate> bySetPos(List<LocalDate> sorted, Integer pos) {
        if (pos == null || sorted.isEmpty()) {
            return sorted;
        }
        if (pos == -1) {
            return List.of(sorted.get(sorted.size() - 1));
        }
        return pos <= sorted.size() ? List.of(sorted.get(pos - 1)) : List.of();
    }
}
