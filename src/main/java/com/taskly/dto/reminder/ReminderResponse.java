package com.taskly.dto.reminder;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.taskly.entity.LocationTrigger;
import com.taskly.entity.ReminderType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Một nhắc riêng của task (spec 12.8). Đúng một trong at / minuteOffset / locationId khác null theo type. at xuất
 * "yyyy-MM-dd'T'HH:mm:ss" (giờ floating, không Z / offset — spec 5.2); createdAt là thời điểm tuyệt đối (Instant, Z).
 * M12 thêm locationId + locationTrigger (LOCATION) và urgent ở cuối.
 */
public record ReminderResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID taskId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReminderType type,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, type = "string",
                example = "2026-10-24T09:00:00", description = "ABSOLUTE: giờ floating; null với loại khác")
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime at,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                description = "RELATIVE: phút trước giờ của task; null với loại khác")
        Integer minuteOffset,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                description = "LOCATION (M12): id địa điểm; null với nhắc theo giờ")
        UUID locationId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                description = "LOCATION (M12): ON_ENTER | ON_LEAVE; null với nhắc theo giờ")
        LocationTrigger locationTrigger,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "M12: urgent reminder (chỉ nhắc theo giờ)")
        boolean urgent) {
}
