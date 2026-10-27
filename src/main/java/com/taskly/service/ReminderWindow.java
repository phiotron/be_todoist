package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Logic thời gian thuần của nhắc việc (spec 5.2, 5.8, 12.8). "Bây giờ" là giờ địa phương theo múi giờ tài khoản —
 * cùng hệ floating với tasks.due_datetime và reminders.at (TIMESTAMP không múi giờ). Test độc lập với DB.
 */
public record ReminderWindow(LocalDateTime now) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 50;
    public static final String AUTO_KEY_PREFIX = "auto:";

    public static ReminderWindow at(Instant instant, ZoneId zone) {
        return new ReminderWindow(LocalDateTime.ofInstant(instant, zone));
    }

    /** Thời điểm đã qua so với bây giờ? Bằng đúng bây giờ thì chưa qua (spec 12.8: fireAt ≥ bây giờ). */
    public boolean hasPassed(LocalDateTime time) {
        return time.isBefore(now);
    }

    /** Thời điểm nhắc lưu theo phút (như due_datetime): cắt giây và phần lẻ. */
    public static LocalDateTime toMinute(LocalDateTime time) {
        return time.truncatedTo(ChronoUnit.MINUTES);
    }

    /** limit của GET tasks/reminders: vắng → 50; ngoài 1..50 → 400 (spec 5.9). */
    public static int limit(Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        if (requested < 1 || requested > MAX_LIMIT) {
            throw ApiException.badRequest(ErrorMessage.REMINDER_LIMIT_RANGE);
        }
        return requested;
    }

    /** Khoá lịch trên máy (spec 5.8): "auto:<taskId>" cho auto reminder, id của nhắc riêng cho nhắc riêng. */
    public static String key(UUID taskId, UUID reminderId) {
        return reminderId == null ? AUTO_KEY_PREFIX + taskId : reminderId.toString();
    }
}
