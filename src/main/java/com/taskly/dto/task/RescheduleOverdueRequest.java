package com.taskly.dto.task;

import com.taskly.common.error.ProblemTexts;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** POST tasks/overdue/reschedule (spec 12.5): ngày mới cho MỌI task quá hạn (quyết định Q37). */
public record RescheduleOverdueRequest(
        @NotNull(message = ProblemTexts.RESCHEDULE_DATE_REQUIRED)
        LocalDate date) {
}
