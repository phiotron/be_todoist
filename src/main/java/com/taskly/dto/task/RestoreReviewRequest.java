package com.taskly.dto.task;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/** Body POST tasks/{id}/review/restore: hoàn tác "Trả lại" — khôi phục yêu cầu chờ duyệt cũ (người yêu cầu + thời điểm). */
public record RestoreReviewRequest(
        @NotNull(message = "{validation.invalid}") UUID requestedBy,
        @NotNull(message = "{validation.invalid}") Instant requestedAt) {
}
