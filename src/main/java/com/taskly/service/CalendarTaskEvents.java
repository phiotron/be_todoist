package com.taskly.service;

import com.taskly.entity.Task;
import com.taskly.integration.calendar.EventDraft;
import com.taskly.integration.calendar.ExternalEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Phần THUẦN của Calendar M13b: task → sự kiện (cả ngày / có giờ, giờ floating hiểu theo múi giờ user), fingerprint, và
 * sự kiện bị sửa trong lịch → due mới cho task (thay đổi ngược có giới hạn).
 */
public final class CalendarTaskEvents {

    /** Link mở task trong app, ghi vào mô tả sự kiện. */
    public static final String TASK_LINK_PREFIX = "taskly://task/";

    private CalendarTaskEvents() {
    }

    /** Task phải có dueDate hoặc dueDatetime. Không có duration → defaultMinutes. */
    public static EventDraft draftOf(Task task, ZoneId zone, int defaultMinutes) {
        String description = task.getDescription() == null || task.getDescription().isBlank()
                ? TASK_LINK_PREFIX + task.getId()
                : task.getDescription() + "\n\n" + TASK_LINK_PREFIX + task.getId();
        if (task.getDueDatetime() == null) {
            return new EventDraft(task.getContent(), description, true, task.getDueDate(), null, null);
        }
        Instant start = task.getDueDatetime().atZone(zone).toInstant();
        int minutes = task.getDurationMinutes() == null ? defaultMinutes : task.getDurationMinutes();
        return new EventDraft(task.getContent(), description, false, null, start, start.plus(Duration.ofMinutes(minutes)));
    }

    /** SHA-256 hex của nội dung đẩy đi: đổi bất kỳ phần nào → cập nhật sự kiện. */
    public static String fingerprint(EventDraft draft) {
        String joined = String.join("\u001f", draft.title(), draft.description(), String.valueOf(draft.allDay()),
                String.valueOf(draft.date()), String.valueOf(draft.start()), String.valueOf(draft.end()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(joined.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Due mới đọc từ sự kiện (cả ngày → dueDate; có giờ → dueDatetime floating theo múi giờ user, phút). */
    public record PulledDue(LocalDate dueDate, LocalDateTime dueDatetime, Integer durationMinutes) {
    }

    /**
     * null = sự kiện không đổi so với task (hoặc không đọc được). durationMinutes chỉ khác null khi độ dài sự kiện khác độ
     * dài đang áp cho task (task không có duration được đẩy với defaultMinutes) và nằm trong 1–1440 phút.
     */
    public static PulledDue pulledDue(ExternalEvent event, Task task, ZoneId zone, int defaultMinutes) {
        if (event.cancelled()) {
            return null;
        }
        if (event.allDay()) {
            if (event.startDate() == null || event.startDate().equals(task.getDueDate()) && task.getDueDatetime() == null) {
                return null;
            }
            return new PulledDue(event.startDate(), null, null);
        }
        if (event.start() == null || event.end() == null) {
            return null;
        }
        LocalDateTime start = LocalDateTime.ofInstant(event.start(), zone).truncatedTo(ChronoUnit.MINUTES);
        long length = Duration.between(event.start(), event.end()).toMinutes();
        int current = task.getDurationMinutes() == null ? defaultMinutes : task.getDurationMinutes();
        Integer duration = length != current && length >= 1 && length <= 1440 ? (int) length : null;
        if (Objects.equals(start, task.getDueDatetime()) && duration == null) {
            return null;
        }
        return new PulledDue(null, start, duration);
    }
}
