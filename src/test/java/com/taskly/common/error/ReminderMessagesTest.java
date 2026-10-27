package com.taskly.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.support.MessageBundles;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** Câu vi / en của M4 (spec 5.8, 5.9, 12.8, 12.10). Hai bundle cùng key do MessageBundlesTest (M3) kiểm. */
class ReminderMessagesTest {

    private static final Properties VI = MessageBundles.load(MessageBundles.VI);
    private static final Properties EN = MessageBundles.load(MessageBundles.EN);

    private static final Map<String, String> VI_TEXTS = Map.of(
            ErrorMessage.REMINDER_NOT_FOUND.key(), "Không tìm thấy nhắc nhở",
            ErrorMessage.REMINDER_FIELDS_MISMATCH.key(),
            "Nhắc tuyệt đối cần at (không có minuteOffset), nhắc tương đối cần minuteOffset (không có at)",
            ErrorMessage.REMINDER_AT_PAST.key(), "Thời điểm nhắc đã qua",
            ErrorMessage.REMINDER_NEEDS_DUE_TIME.key(), "Nhắc tương đối chỉ dùng cho công việc có giờ",
            ErrorMessage.REMINDER_TASK_COMPLETED.key(), "Công việc đã hoàn thành, không thêm nhắc được",
            ErrorMessage.REMINDER_LIMIT_RANGE.key(), "limit phải từ 1 đến 50",
            "validation.reminder.type.required", "Vui lòng chọn loại nhắc",
            "validation.reminder.minute-offset.range", "Nhắc trước phải từ 0 đến 10080 phút (7 ngày)",
            "validation.auto-reminder-minutes.allowed",
            "Nhắc tự động chỉ nhận 0, 5, 10, 15, 30, 45, 60, 120, 180 hoặc 1440 phút");

    private static final Map<String, String> EN_TEXTS = Map.of(
            ErrorMessage.REMINDER_NOT_FOUND.key(), "Reminder not found",
            ErrorMessage.REMINDER_FIELDS_MISMATCH.key(),
            "An absolute reminder needs at (no minuteOffset), a relative reminder needs minuteOffset (no at)",
            ErrorMessage.REMINDER_AT_PAST.key(), "The reminder time has already passed",
            ErrorMessage.REMINDER_NEEDS_DUE_TIME.key(), "Relative reminders only work for tasks with a due time",
            ErrorMessage.REMINDER_TASK_COMPLETED.key(), "The task is completed, reminders cannot be added",
            ErrorMessage.REMINDER_LIMIT_RANGE.key(), "limit must be between 1 and 50",
            "validation.reminder.type.required", "Please choose a reminder type",
            "validation.reminder.minute-offset.range", "Reminder offset must be between 0 and 10080 minutes (7 days)",
            "validation.auto-reminder-minutes.allowed",
            "Automatic reminder must be 0, 5, 10, 15, 30, 45, 60, 120, 180 or 1440 minutes");

    @Test
    void vietnameseTexts() {
        VI_TEXTS.forEach((key, text) -> assertThat(VI.getProperty(key)).as(key).isEqualTo(text));
    }

    @Test
    void englishTexts() {
        EN_TEXTS.forEach((key, text) -> assertThat(EN.getProperty(key)).as(key).isEqualTo(text));
    }
}
