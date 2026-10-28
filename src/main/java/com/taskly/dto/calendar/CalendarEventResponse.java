package com.taskly.dto.calendar;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Một sự kiện lịch CHỈ ĐỌC (M13b). Giờ đã đổi sang múi giờ của user (floating như task, spec 5.2). allDay: startDate /
 * endDate (cả hai tính, endDate là ngày cuối cùng); có giờ: thêm startDatetime / endDatetime.
 */
public record CalendarEventResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean allDay,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate startDate,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate endDate,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) LocalDateTime startDatetime,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) LocalDateTime endDatetime,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String location) {
}
