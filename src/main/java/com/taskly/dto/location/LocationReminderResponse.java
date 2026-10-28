package com.taskly.dto.location;

import com.taskly.entity.LocationTrigger;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** Một nhắc theo vị trí app cần đăng ký geofence (GET /api/v1/tasks/location-reminders, M12). */
public record LocationReminderResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Id nhắc — dùng làm id vùng geofence")
        UUID reminderId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID taskId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Tên task — nội dung thông báo")
        String content,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID locationId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Tên địa điểm") String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double latitude,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double longitude,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Bán kính, mét") int radius,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocationTrigger trigger) {
}
