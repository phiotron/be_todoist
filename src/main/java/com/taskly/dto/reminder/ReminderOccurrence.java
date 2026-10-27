package com.taskly.dto.reminder;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.taskly.entity.ReminderKind;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;

/** Một lần nhắc app cần đặt lịch trên máy (spec 5.8, 12.8). */
public record ReminderOccurrence(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Id lịch trên máy: auto:<taskId> với AUTO, id nhắc riêng với ABSOLUTE / RELATIVE")
        String key,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID taskId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, description = "null với AUTO")
        UUID reminderId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReminderKind kind,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", example = "2026-10-24T08:30:00",
                description = "Thời điểm kích hoạt, giờ floating theo múi giờ tài khoản (không Z / offset)")
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime fireAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Tên task — nội dung thông báo")
        String content) {
}
