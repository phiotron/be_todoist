package com.taskly.dto.calendar;

import com.taskly.entity.CalendarProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** GET me/calendar (M13b). connected = false thì các field còn lại là null / false. */
public record CalendarStatusResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean connected,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) CalendarProvider provider,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String accountEmail,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Hiện sự kiện (chỉ đọc) trong Hôm nay / Sắp tới") boolean showEvents,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Đồng bộ task có lịch sang lịch riêng \"Taskly\"") boolean syncTasks,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Nhà cung cấp đã thu hồi quyền: ngắt rồi kết nối lại") boolean needsReauth,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Instant lastSyncedAt) {

    public static CalendarStatusResponse disconnected() {
        return new CalendarStatusResponse(false, null, null, false, false, false, null);
    }
}
