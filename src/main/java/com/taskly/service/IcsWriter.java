package com.taskly.service;

import com.taskly.entity.Task;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Viết calendar feed iCalendar (RFC 5545) từ danh sách task — phần THUẦN của M13b. Mỗi task một VEVENT: cả ngày
 * (DTSTART;VALUE=DATE) hoặc có giờ (giờ floating của task hiểu theo múi giờ user, ghi UTC "Z"). Task lặp: RRULE của task
 * + COUNT=7 (Todoist: feed chỉ hiện tối đa 7 lần); rule đã có UNTIL thì giữ UNTIL (RFC cấm COUNT cùng UNTIL). Dòng kết thúc
 * CRLF, gập ở 75 octet, ký tự đặc biệt được thoát.
 */
public final class IcsWriter {

    public static final int MAX_OCCURRENCES = 7;
    private static final String CRLF = "\r\n";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    private IcsWriter() {
    }

    public static String write(String calendarName, List<Task> tasks, ZoneId zone, int defaultMinutes, Instant now) {
        List<String> lines = new ArrayList<>();
        lines.add("BEGIN:VCALENDAR");
        lines.add("VERSION:2.0");
        lines.add("PRODID:-//Taskly//Calendar feed//EN");
        lines.add("CALSCALE:GREGORIAN");
        lines.add("METHOD:PUBLISH");
        lines.add("X-WR-CALNAME:" + escape(calendarName));
        lines.add("X-WR-TIMEZONE:" + zone.getId());
        for (Task task : tasks) {
            if (task.getDueDate() == null && task.getDueDatetime() == null) {
                continue;
            }
            lines.add("BEGIN:VEVENT");
            lines.add("UID:" + task.getId() + "@taskly");
            lines.add("DTSTAMP:" + UTC.format(now));
            lines.add("SUMMARY:" + escape(task.getContent()));
            String description = task.getDescription() == null || task.getDescription().isBlank()
                    ? CalendarTaskEvents.TASK_LINK_PREFIX + task.getId()
                    : task.getDescription() + "\n\n" + CalendarTaskEvents.TASK_LINK_PREFIX + task.getId();
            lines.add("DESCRIPTION:" + escape(description));
            if (task.getDueDatetime() == null) {
                lines.add("DTSTART;VALUE=DATE:" + DATE.format(task.getDueDate()));
                lines.add("DTEND;VALUE=DATE:" + DATE.format(task.getDueDate().plusDays(1)));
            } else {
                Instant start = task.getDueDatetime().atZone(zone).toInstant();
                int minutes = task.getDurationMinutes() == null ? defaultMinutes : task.getDurationMinutes();
                lines.add("DTSTART:" + UTC.format(start));
                lines.add("DTEND:" + UTC.format(start.plusSeconds(60L * minutes)));
            }
            String rrule = limitedRule(task.getRecurrenceRule());
            if (rrule != null) {
                lines.add("RRULE:" + rrule);
            }
            lines.add("END:VEVENT");
        }
        lines.add("END:VCALENDAR");
        StringBuilder out = new StringBuilder();
        lines.forEach(line -> out.append(fold(line)).append(CRLF));
        return out.toString();
    }

    /** "RRULE:FREQ=…" hoặc "FREQ=…" → rule có COUNT=7 (COUNT cũ bị thay); có UNTIL → giữ nguyên UNTIL. null = không lặp. */
    static String limitedRule(String rule) {
        if (rule == null || rule.isBlank()) {
            return null;
        }
        String body = rule.strip();
        if (body.toUpperCase(Locale.ROOT).startsWith("RRULE:")) {
            body = body.substring("RRULE:".length());
        }
        List<String> parts = new ArrayList<>();
        boolean hasUntil = false;
        for (String part : body.split(";")) {
            String upper = part.toUpperCase(Locale.ROOT);
            if (part.isBlank() || upper.startsWith("COUNT=")) {
                continue;
            }
            hasUntil |= upper.startsWith("UNTIL=");
            parts.add(part);
        }
        if (parts.stream().noneMatch(part -> part.toUpperCase(Locale.ROOT).startsWith("FREQ="))) {
            return null;
        }
        if (!hasUntil) {
            parts.add("COUNT=" + MAX_OCCURRENCES);
        }
        return String.join(";", parts);
    }

    static String escape(String text) {
        return text.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
                .replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");
    }

    /** Gập dòng dài hơn 75 octet (UTF-8), không cắt giữa một ký tự. */
    static String fold(String line) {
        if (line.getBytes(StandardCharsets.UTF_8).length <= 75) {
            return line;
        }
        StringBuilder out = new StringBuilder();
        int octets = 0;
        int limit = 75;
        for (int i = 0; i < line.length(); ) {
            int codePoint = line.codePointAt(i);
            String ch = new String(Character.toChars(codePoint));
            int size = ch.getBytes(StandardCharsets.UTF_8).length;
            if (octets + size > limit) {
                out.append(CRLF).append(' ');
                octets = 1;
                limit = 75;
            }
            out.append(ch);
            octets += size;
            i += Character.charCount(codePoint);
        }
        return out.toString();
    }
}
