package com.taskly.dto.reminder;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.taskly.common.error.ProblemTexts;
import com.taskly.entity.ReminderType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * POST tasks/{id}/reminders (spec 12.8). ABSOLUTE cần at (không minuteOffset); RELATIVE cần minuteOffset (không at)
 * — kiểm ở ReminderService (400 error.reminder-fields-mismatch). at là giờ floating (spec 5.2): nhận
 * "yyyy-MM-ddTHH:mm" hoặc có giây; có Z / offset / chuỗi rác → 400 (Jackson).
 */
public record CreateReminderRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = ProblemTexts.REMINDER_TYPE_REQUIRED)
        ReminderType type,

        @Schema(type = "string", example = "2026-10-24T09:00",
                description = "ABSOLUTE: thời điểm nhắc floating theo múi giờ tài khoản, yyyy-MM-ddTHH:mm (giây bị cắt)")
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
        LocalDateTime at,

        @Schema(description = "RELATIVE: số phút trước giờ của task (0–10080)", example = "30")
        @Min(value = 0, message = ProblemTexts.REMINDER_MINUTE_OFFSET_RANGE)
        @Max(value = CreateReminderRequest.MAX_MINUTE_OFFSET, message = ProblemTexts.REMINDER_MINUTE_OFFSET_RANGE)
        Integer minuteOffset) {

    /**
     * minuteOffset tối đa của nhắc tương đối = 7 ngày (spec 5.9). Hằng thuộc DTO (service import DTO, không ngược lại);
     * viết có tiền tố tên record vì phần đầu record chưa nằm trong phạm vi tên của thân record.
     */
    public static final int MAX_MINUTE_OFFSET = 10_080;
}
