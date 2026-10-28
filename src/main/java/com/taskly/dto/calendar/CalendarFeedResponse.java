package com.taskly.dto.calendar;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** Link iCal (có token) để dán vào Google Calendar / Outlook / Apple Calendar ("Subscribe from URL"). */
public record CalendarFeedResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String url,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) UUID projectId) {
}
