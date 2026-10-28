package com.taskly.dto.calendar;

import java.util.UUID;

/** POST me/calendar-feeds[/reset] (M13b): projectId vắng / null = feed của cả tài khoản. */
public record CalendarFeedRequest(UUID projectId) {
}
