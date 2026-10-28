package com.taskly.dto.reminder;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.taskly.common.error.ErrorMessage;
import com.taskly.entity.LocationTrigger;
import com.taskly.entity.ReminderType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * POST tasks/{id}/reminders (spec 12.8). ABSOLUTE cần at (không minuteOffset); RELATIVE cần minuteOffset (không at)
 * — kiểm ở ReminderService (400 error.reminder-fields-mismatch). at là giờ floating (spec 5.2): nhận
 * "yyyy-MM-ddTHH:mm" hoặc có giây; có Z / offset / chuỗi rác → 400 (Jackson).
 * M12: LOCATION cần locationId + trigger (không at / minuteOffset; error.reminder-location-fields); nhắc theo giờ không
 * được kèm locationId / trigger. urgent (vắng = false) chỉ cho ABSOLUTE / RELATIVE (error.reminder-urgent-location).
 */
public record CreateReminderRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "{validation.reminder.type.required}")
        ReminderType type,

        @Schema(type = "string", example = "2026-10-24T09:00",
                description = "ABSOLUTE: thời điểm nhắc floating theo múi giờ tài khoản, yyyy-MM-ddTHH:mm (giây bị cắt)")
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
        LocalDateTime at,

        @Schema(description = "RELATIVE: số phút trước giờ của task (0–10080)", example = "30")
        @Min(value = 0, message = "{validation.reminder.minute-offset.range}")
        @Max(value = CreateReminderRequest.MAX_MINUTE_OFFSET, message = "{validation.reminder.minute-offset.range}")
        Integer minuteOffset,

        @Schema(description = "LOCATION (M12): id địa điểm đã lưu của chính mình")
        UUID locationId,

        @Schema(description = "LOCATION (M12): ON_ENTER = khi tới, ON_LEAVE = khi rời")
        LocationTrigger trigger,

        @Schema(description = "M12: urgent reminder (chỉ ABSOLUTE / RELATIVE); vắng = false")
        Boolean urgent) {

    /**
     * minuteOffset tối đa của nhắc tương đối = 7 ngày (spec 5.9). Hằng thuộc DTO (service import DTO, không ngược lại);
     * viết có tiền tố tên record vì phần đầu record chưa nằm trong phạm vi tên của thân record.
     */
    public static final int MAX_MINUTE_OFFSET = 10_080;

    /** urgent vắng / null = false. */
    public boolean urgentOrFalse() {
        return Boolean.TRUE.equals(urgent);
    }
}
