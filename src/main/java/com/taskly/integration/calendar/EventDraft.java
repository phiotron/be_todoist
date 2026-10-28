package com.taskly.integration.calendar;

import java.time.Instant;
import java.time.LocalDate;

/** Nội dung sự kiện đẩy từ task sang lịch "Taskly": cả ngày (date) hoặc có giờ (start / end UTC). */
public record EventDraft(String title, String description, boolean allDay, LocalDate date, Instant start,
                         Instant end) {
}
