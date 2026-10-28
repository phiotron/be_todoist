package com.taskly.integration.calendar;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Sự kiện đọc từ lịch ngoài. allDay → startDate + endDateExclusive (ngày, như iCal); không → start / end (Instant).
 * cancelled = đã huỷ / xoá (chỉ có ở changedEvents).
 */
public record ExternalEvent(String id, String title, boolean allDay, LocalDate startDate, LocalDate endDateExclusive,
                            Instant start, Instant end, String location, boolean cancelled) {

    public static ExternalEvent allDay(String id, String title, LocalDate start, LocalDate endExclusive) {
        return new ExternalEvent(id, title, true, start, endExclusive, null, null, null, false);
    }

    public static ExternalEvent timed(String id, String title, Instant start, Instant end) {
        return new ExternalEvent(id, title, false, null, null, start, end, null, false);
    }

    public static ExternalEvent cancelled(String id) {
        return new ExternalEvent(id, null, false, null, null, null, null, null, true);
    }

    public ExternalEvent withLocation(String value) {
        return new ExternalEvent(id, title, allDay, startDate, endDateExclusive, start, end, value, cancelled);
    }
}
