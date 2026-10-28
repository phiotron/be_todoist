package com.taskly.dto.calendar;

import com.taskly.entity.CalendarProvider;
import jakarta.validation.constraints.NotNull;

/** POST me/calendar/connect (M13b). */
public record ConnectCalendarRequest(
        @NotNull(message = "{validation.calendar.provider.required}") CalendarProvider provider) {
}
