package com.taskly.dto.calendar;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** GET calendar/events (M13b). needsReauth = true: quyền đã bị thu hồi, items rỗng. */
public record CalendarEventsResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<CalendarEventResponse> items,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean needsReauth) {
}
