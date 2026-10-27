package com.taskly.dto.task;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** POST tasks/overdue/reschedule (spec 12.5): ngày mới cho MỌI task quá hạn (quyết định Q37). */
public record RescheduleOverdueRequest(
        @NotNull(message = "{error.reschedule-date-required}")
        LocalDate date) {
}
