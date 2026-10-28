package com.taskly.dto.calendar;

/** PATCH me/calendar (M13b): field vắng / null = giữ nguyên. */
public record UpdateCalendarRequest(Boolean showEvents, Boolean syncTasks) {
}
