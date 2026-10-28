package com.taskly.service.email;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bộ đọc ngày TIẾNG ANH tối giản cho cú pháp {@code date …} trong tiêu đề thư (M13a). THUẦN (không Spring), "hôm nay"
 * truyền vào (theo múi giờ tài khoản). Parser Quick Add đầy đủ (M6b) chạy ở mobile; BE chỉ cần tập con này:
 * <ul>
 *   <li>ngày: {@code today|tod}, {@code tomorrow|tom}, thứ ({@code mon}…{@code sunday}: hôm nay nếu trùng, không thì lần
 *   tới), {@code next week} (thứ Hai tới), {@code in N day(s)|week(s)|month(s)} (N ≥ 1), {@code yyyy-MM-dd},
 *   {@code 5 nov} / {@code nov 5} / {@code nov 5th} (+ năm 4 chữ số tuỳ chọn; không năm mà đã qua → năm sau);</li>
 *   <li>giờ (sau ngày, hoặc đứng một mình = hôm nay): {@code [at] 17:30}, {@code 5pm}, {@code 5:30pm}, {@code 5 pm}.</li>
 * </ul>
 * Phải đọc HẾT các token, không thì trả rỗng (caller thử tiền tố ngắn hơn). Không hiểu hoa / thường, bỏ dấu , . ; cuối token.
 */
public final class EmailDateParser {

    /** time null = cả ngày (dueDate); có giờ = dueDatetime. */
    public record Result(LocalDate date, LocalTime time) {
    }

    private static final Map<String, DayOfWeek> WEEKDAYS = Map.ofEntries(
            Map.entry("mon", DayOfWeek.MONDAY), Map.entry("monday", DayOfWeek.MONDAY),
            Map.entry("tue", DayOfWeek.TUESDAY), Map.entry("tues", DayOfWeek.TUESDAY),
            Map.entry("tuesday", DayOfWeek.TUESDAY),
            Map.entry("wed", DayOfWeek.WEDNESDAY), Map.entry("wednesday", DayOfWeek.WEDNESDAY),
            Map.entry("thu", DayOfWeek.THURSDAY), Map.entry("thur", DayOfWeek.THURSDAY),
            Map.entry("thurs", DayOfWeek.THURSDAY), Map.entry("thursday", DayOfWeek.THURSDAY),
            Map.entry("fri", DayOfWeek.FRIDAY), Map.entry("friday", DayOfWeek.FRIDAY),
            Map.entry("sat", DayOfWeek.SATURDAY), Map.entry("saturday", DayOfWeek.SATURDAY),
            Map.entry("sun", DayOfWeek.SUNDAY), Map.entry("sunday", DayOfWeek.SUNDAY));

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("jan", 1), Map.entry("january", 1), Map.entry("feb", 2), Map.entry("february", 2),
            Map.entry("mar", 3), Map.entry("march", 3), Map.entry("apr", 4), Map.entry("april", 4),
            Map.entry("may", 5), Map.entry("jun", 6), Map.entry("june", 6), Map.entry("jul", 7), Map.entry("july", 7),
            Map.entry("aug", 8), Map.entry("august", 8), Map.entry("sep", 9), Map.entry("sept", 9),
            Map.entry("september", 9), Map.entry("oct", 10), Map.entry("october", 10), Map.entry("nov", 11),
            Map.entry("november", 11), Map.entry("dec", 12), Map.entry("december", 12));

    private static final Pattern ISO_DATE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
    private static final Pattern DAY_OF_MONTH = Pattern.compile("(\\d{1,2})(st|nd|rd|th)?");
    private static final Pattern YEAR = Pattern.compile("\\d{4}");
    private static final Pattern NUMBER = Pattern.compile("\\d{1,3}");
    private static final Pattern CLOCK_24 = Pattern.compile("(\\d{1,2}):(\\d{2})");
    private static final Pattern CLOCK_12 = Pattern.compile("(\\d{1,2})(?::(\\d{2}))?(am|pm)");
    private static final Pattern HOUR_ONLY = Pattern.compile("\\d{1,2}");

    private EmailDateParser() {
    }

    public static Optional<Result> parse(List<String> rawTokens, LocalDate today) {
        if (rawTokens.isEmpty()) {
            return Optional.empty();
        }
        Cursor cursor = new Cursor(rawTokens.stream().map(EmailDateParser::normalize).toList());
        LocalDate date = readDate(cursor, today);
        LocalTime time = readTime(cursor);
        if (!cursor.atEnd() || (date == null && time == null)) {
            return Optional.empty();
        }
        return Optional.of(new Result(date == null ? today : date, time));
    }

    private static String normalize(String token) {
        return token.toLowerCase(Locale.ROOT).replaceAll("[,.;]+$", "");
    }

    // ---- ngày ----

    private static LocalDate readDate(Cursor c, LocalDate today) {
        String token = c.peek();
        if (token == null) {
            return null;
        }
        switch (token) {
            case "today", "tod" -> {
                c.advance(1);
                return today;
            }
            case "tomorrow", "tom" -> {
                c.advance(1);
                return today.plusDays(1);
            }
            default -> {
                // tiếp tục bên dưới
            }
        }
        DayOfWeek weekday = WEEKDAYS.get(token);
        if (weekday != null) {
            c.advance(1);
            return today.with(TemporalAdjusters.nextOrSame(weekday));
        }
        if (token.equals("next") && "week".equals(c.peek(1))) {
            c.advance(2);
            return today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        }
        if (token.equals("in")) {
            return readRelative(c, today);
        }
        Matcher iso = ISO_DATE.matcher(token);
        if (iso.matches()) {
            LocalDate date = safeDate(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)),
                    Integer.parseInt(iso.group(3)));
            if (date != null) {
                c.advance(1);
            }
            return date;
        }
        return readDayMonth(c, today);
    }

    private static LocalDate readRelative(Cursor c, LocalDate today) {
        String amount = c.peek(1);
        String unit = c.peek(2);
        if (amount == null || unit == null || !NUMBER.matcher(amount).matches()) {
            return null;
        }
        int n = Integer.parseInt(amount);
        if (n < 1) {
            return null;
        }
        LocalDate date = switch (unit) {
            case "day", "days" -> today.plusDays(n);
            case "week", "weeks" -> today.plusWeeks(n);
            case "month", "months" -> today.plusMonths(n);
            default -> null;
        };
        if (date != null) {
            c.advance(3);
        }
        return date;
    }

    /** {@code 5 nov [2027]}, {@code nov 5[th] [2027]}. */
    private static LocalDate readDayMonth(Cursor c, LocalDate today) {
        String first = c.peek();
        String second = c.peek(1);
        if (second == null) {
            return null;
        }
        Integer month;
        Matcher day;
        if (MONTHS.containsKey(first)) {
            month = MONTHS.get(first);
            day = DAY_OF_MONTH.matcher(second);
        } else {
            month = MONTHS.get(second);
            day = DAY_OF_MONTH.matcher(first);
        }
        if (month == null || !day.matches()) {
            return null;
        }
        int dayOfMonth = Integer.parseInt(day.group(1));
        String yearToken = c.peek(2);
        if (yearToken != null && YEAR.matcher(yearToken).matches()) {
            LocalDate date = safeDate(Integer.parseInt(yearToken), month, dayOfMonth);
            if (date != null) {
                c.advance(3);
            }
            return date;
        }
        LocalDate date = safeDate(today.getYear(), month, dayOfMonth);
        if (date == null) {
            return null;
        }
        c.advance(2);
        return date.isBefore(today) ? safeDate(today.getYear() + 1, month, dayOfMonth) : date;
    }

    private static LocalDate safeDate(int year, int month, int day) {
        try {
            return LocalDate.of(year, month, day);
        } catch (DateTimeException e) {
            return null;
        }
    }

    // ---- giờ ----

    private static LocalTime readTime(Cursor c) {
        int start = c.position();
        if ("at".equals(c.peek())) {
            c.advance(1);
        }
        LocalTime time = readClock(c);
        if (time == null) {
            c.reset(start);
        }
        return time;
    }

    private static LocalTime readClock(Cursor c) {
        String token = c.peek();
        if (token == null) {
            return null;
        }
        Matcher h24 = CLOCK_24.matcher(token);
        if (h24.matches()) {
            LocalTime time = safeTime(Integer.parseInt(h24.group(1)), Integer.parseInt(h24.group(2)));
            if (time != null) {
                c.advance(1);
            }
            return time;
        }
        Matcher h12 = CLOCK_12.matcher(token);
        if (h12.matches()) {
            LocalTime time = twelveHour(h12.group(1), h12.group(2), h12.group(3));
            if (time != null) {
                c.advance(1);
            }
            return time;
        }
        String next = c.peek(1);
        if (HOUR_ONLY.matcher(token).matches() && ("am".equals(next) || "pm".equals(next))) {
            LocalTime time = twelveHour(token, null, next);
            if (time != null) {
                c.advance(2);
            }
            return time;
        }
        return null;
    }

    private static LocalTime twelveHour(String hourText, String minuteText, String meridiem) {
        int hour = Integer.parseInt(hourText);
        int minute = minuteText == null ? 0 : Integer.parseInt(minuteText);
        if (hour < 1 || hour > 12) {
            return null;
        }
        int hour24 = hour % 12 + ("pm".equals(meridiem) ? 12 : 0);
        return safeTime(hour24, minute);
    }

    private static LocalTime safeTime(int hour, int minute) {
        try {
            return LocalTime.of(hour, minute);
        } catch (DateTimeException e) {
            return null;
        }
    }

    /** Con trỏ trên danh sách token đã chuẩn hoá. */
    private static final class Cursor {
        private final List<String> tokens;
        private int index;

        Cursor(List<String> tokens) {
            this.tokens = tokens;
        }

        String peek() {
            return peek(0);
        }

        String peek(int offset) {
            int i = index + offset;
            return i < tokens.size() ? tokens.get(i) : null;
        }

        void advance(int n) {
            index += n;
        }

        int position() {
            return index;
        }

        void reset(int position) {
            index = position;
        }

        boolean atEnd() {
            return index >= tokens.size();
        }
    }
}
