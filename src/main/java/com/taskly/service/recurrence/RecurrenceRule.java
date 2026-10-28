package com.taskly.service.recurrence;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RRULE RÚT GỌN (RFC 5545, contract 8.1) — không tiền tố "RRULE:", không COUNT / BYHOUR / BYMINUTE (giờ lấy từ due).
 * Tập con: FREQ ∈ DAILY|WEEKLY|MONTHLY|YEARLY; INTERVAL 1–999; BYDAY (MO…SU, thứ tự 1–4 | -1 chỉ với MONTHLY / YEARLY);
 * BYMONTHDAY (1–31 | -1, không với WEEKLY); BYMONTH (1–12); BYSETPOS (-1 | 1–4, cần BYDAY); UNTIL yyyyMMdd (gồm ngày cuối).
 * Phân biệt hoa thường, không khoảng trắng, mỗi khoá một lần. Dạng chuẩn (format): FREQ;INTERVAL(≠1);BYMONTH;BYMONTHDAY;
 * BYDAY;BYSETPOS;UNTIL — mobile lib/recurrence/rrule.ts dùng cùng thứ tự. THUẦN (không Spring).
 */
public record RecurrenceRule(Freq freq, int interval, List<WeekdayNum> byDay, List<Integer> byMonthDay,
                             List<Integer> byMonth, Integer bySetPos, LocalDate until) {

    public static final int MAX_LENGTH = 500;
    public static final int MAX_INTERVAL = 999;

    public enum Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

    /** Một phần tử BYDAY: thứ + thứ tự trong tháng (null = mọi thứ đó; 1–4; -1 = cuối). */
    public record WeekdayNum(DayOfWeek weekday, Integer ordinal) {

        String format() {
            return (ordinal == null ? "" : ordinal.toString()) + code(weekday);
        }
    }

    private static final Pattern BYDAY_ITEM = Pattern.compile("(-1|[1-4])?(MO|TU|WE|TH|FR|SA|SU)");
    private static final Pattern INT = Pattern.compile("-?[0-9]{1,3}");
    private static final Pattern KEY_VALUE = Pattern.compile("([A-Z]+)=([A-Z0-9,\\-]+)");
    private static final Map<DayOfWeek, String> CODES = new EnumMap<>(DayOfWeek.class);

    static {
        CODES.put(DayOfWeek.MONDAY, "MO");
        CODES.put(DayOfWeek.TUESDAY, "TU");
        CODES.put(DayOfWeek.WEDNESDAY, "WE");
        CODES.put(DayOfWeek.THURSDAY, "TH");
        CODES.put(DayOfWeek.FRIDAY, "FR");
        CODES.put(DayOfWeek.SATURDAY, "SA");
        CODES.put(DayOfWeek.SUNDAY, "SU");
    }

    public RecurrenceRule {
        byDay = List.copyOf(byDay);
        byMonthDay = List.copyOf(byMonthDay);
        byMonth = List.copyOf(byMonth);
    }

    /** Đọc chuỗi rule; ngoài tập con → InvalidRecurrenceException. */
    public static RecurrenceRule parse(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_LENGTH) {
            throw new InvalidRecurrenceException();
        }
        Map<String, String> parts = new java.util.HashMap<>();
        for (String part : text.split(";", -1)) {
            Matcher m = KEY_VALUE.matcher(part);
            if (!m.matches() || parts.put(m.group(1), m.group(2)) != null) {
                throw new InvalidRecurrenceException();
            }
        }
        Set<String> allowed = Set.of("FREQ", "INTERVAL", "BYDAY", "BYMONTHDAY", "BYMONTH", "BYSETPOS", "UNTIL");
        if (!allowed.containsAll(parts.keySet()) || !parts.containsKey("FREQ")) {
            throw new InvalidRecurrenceException();
        }
        Freq freq;
        try {
            freq = Freq.valueOf(parts.get("FREQ"));
        } catch (IllegalArgumentException e) {
            throw new InvalidRecurrenceException();
        }
        int interval = parts.containsKey("INTERVAL") ? positiveInt(parts.get("INTERVAL"), 1, MAX_INTERVAL) : 1;
        List<WeekdayNum> byDay = parts.containsKey("BYDAY") ? byDay(parts.get("BYDAY"), freq) : List.of();
        List<Integer> byMonthDay = List.of();
        if (parts.containsKey("BYMONTHDAY")) {
            if (freq == Freq.WEEKLY) {
                throw new InvalidRecurrenceException();
            }
            byMonthDay = intList(parts.get("BYMONTHDAY"), -1, 31);
        }
        List<Integer> byMonth = parts.containsKey("BYMONTH") ? intList(parts.get("BYMONTH"), 1, 12) : List.of();
        Integer bySetPos = null;
        if (parts.containsKey("BYSETPOS")) {
            bySetPos = integer(parts.get("BYSETPOS"));
            if (byDay.isEmpty() || !(bySetPos == -1 || (bySetPos >= 1 && bySetPos <= 4))) {
                throw new InvalidRecurrenceException();
            }
        }
        LocalDate until = parts.containsKey("UNTIL") ? untilDate(parts.get("UNTIL")) : null;
        return new RecurrenceRule(freq, interval, byDay, byMonthDay, byMonth, bySetPos, until);
    }

    /** Dạng chuẩn (thứ tự khoá cố định, bỏ INTERVAL=1). */
    public String format() {
        StringJoiner out = new StringJoiner(";");
        out.add("FREQ=" + freq);
        if (interval != 1) {
            out.add("INTERVAL=" + interval);
        }
        if (!byMonth.isEmpty()) {
            out.add("BYMONTH=" + join(byMonth));
        }
        if (!byMonthDay.isEmpty()) {
            out.add("BYMONTHDAY=" + join(byMonthDay));
        }
        if (!byDay.isEmpty()) {
            StringJoiner days = new StringJoiner(",");
            byDay.forEach(d -> days.add(d.format()));
            out.add("BYDAY=" + days);
        }
        if (bySetPos != null) {
            out.add("BYSETPOS=" + bySetPos);
        }
        if (until != null) {
            out.add("UNTIL=" + until.toString().replace("-", ""));
        }
        return out.toString();
    }

    /** Có phần BY* (BYDAY / BYMONTHDAY / BYMONTH) — `every!` không BY* thì cộng thẳng N đơn vị vào ngày hoàn thành. */
    public boolean hasByParts() {
        return !byDay.isEmpty() || !byMonthDay.isEmpty() || !byMonth.isEmpty();
    }

    /**
     * Neo kỳ theo due khi lưu rule `every` (plan M6c QT 2): WEEKLY thiếu BYDAY → thứ của due; MONTHLY thiếu BYDAY và
     * BYMONTHDAY → ngày của due; YEARLY thiếu cả ba → tháng + ngày của due. Nhờ vậy "ngày 31 hằng tháng" không trôi về 28
     * sau tháng 2. Rule đã có neo / DAILY giữ nguyên.
     */
    public RecurrenceRule anchoredTo(LocalDate due) {
        return switch (freq) {
            case DAILY -> this;
            case WEEKLY -> byDay.isEmpty()
                    ? new RecurrenceRule(freq, interval, List.of(new WeekdayNum(due.getDayOfWeek(), null)), byMonthDay,
                    byMonth, bySetPos, until)
                    : this;
            case MONTHLY -> byDay.isEmpty() && byMonthDay.isEmpty()
                    ? new RecurrenceRule(freq, interval, byDay, List.of(due.getDayOfMonth()), byMonth, bySetPos, until)
                    : this;
            case YEARLY -> !hasByParts()
                    ? new RecurrenceRule(freq, interval, byDay, List.of(due.getDayOfMonth()), List.of(due.getMonthValue()),
                    bySetPos, until)
                    : this;
        };
    }

    static String code(DayOfWeek day) {
        return CODES.get(day);
    }

    private static List<WeekdayNum> byDay(String value, Freq freq) {
        List<WeekdayNum> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String item : value.split(",", -1)) {
            Matcher m = BYDAY_ITEM.matcher(item);
            if (!m.matches() || !seen.add(item)) {
                throw new InvalidRecurrenceException();
            }
            Integer ordinal = m.group(1) == null ? null : Integer.valueOf(m.group(1));
            if (ordinal != null && (freq == Freq.DAILY || freq == Freq.WEEKLY)) {
                throw new InvalidRecurrenceException();
            }
            DayOfWeek day = CODES.entrySet().stream().filter(e -> e.getValue().equals(m.group(2))).findFirst()
                    .orElseThrow(InvalidRecurrenceException::new).getKey();
            result.add(new WeekdayNum(day, ordinal));
        }
        return result;
    }

    private static List<Integer> intList(String value, int min, int max) {
        List<Integer> result = new ArrayList<>();
        for (String item : value.split(",", -1)) {
            int n = integer(item);
            boolean ok = min == -1 ? (n == -1 || (n >= 1 && n <= max)) : (n >= min && n <= max);
            if (!ok || result.contains(n)) {
                throw new InvalidRecurrenceException();
            }
            result.add(n);
        }
        return result;
    }

    private static int positiveInt(String value, int min, int max) {
        int n = integer(value);
        if (n < min || n > max) {
            throw new InvalidRecurrenceException();
        }
        return n;
    }

    private static int integer(String value) {
        if (!INT.matcher(value).matches()) {
            throw new InvalidRecurrenceException();
        }
        return Integer.parseInt(value);
    }

    private static LocalDate untilDate(String value) {
        if (!value.matches("[0-9]{8}")) {
            throw new InvalidRecurrenceException();
        }
        try {
            return LocalDate.of(Integer.parseInt(value.substring(0, 4)), Integer.parseInt(value.substring(4, 6)),
                    Integer.parseInt(value.substring(6, 8)));
        } catch (DateTimeException e) {
            throw new InvalidRecurrenceException();
        }
    }

    private static String join(List<Integer> values) {
        StringJoiner out = new StringJoiner(",");
        values.forEach(v -> out.add(v.toString()));
        return out.toString();
    }
}
