package com.taskly.dto.task;

import com.taskly.common.error.ProblemTexts;
import com.taskly.entity.BatchAction;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * POST tasks/batch (spec 12.5). Trường theo action: REOPEN → undo; MOVE → projectId (+ sectionId, vắng = không
 * section); SCHEDULE → date (BẮT BUỘC có khoá: ngày yyyy-MM-dd hoặc null = bỏ due); PRIORITY → priority 1–4; ADD_LABELS / REMOVE_LABELS → labelIds (không rỗng).
 * date là Optional để phân biệt "vắng khoá" (null) với "gửi null = bỏ due" (Optional.empty) — quyết định Q7 / Q36.
 * Trường không thuộc action → 400 (TaskBatchService.validate).
 */
public record BatchTasksRequest(
        @NotNull(message = ProblemTexts.BATCH_ACTION_REQUIRED)
        BatchAction action,

        @NotNull(message = ProblemTexts.BATCH_IDS_SIZE)
        @Size(min = 1, max = 200, message = ProblemTexts.BATCH_IDS_SIZE)
        List<UUID> taskIds,

        Boolean undo,

        UUID projectId,

        UUID sectionId,

        @Schema(type = "string", format = "date", nullable = true, example = "2026-10-27",
                description = "Chỉ SCHEDULE: bắt buộc có khoá; null = bỏ due (xoá duration, nhắc tương đối)")
        Optional<LocalDate> date,

        @Min(value = 1, message = ProblemTexts.PRIORITY_RANGE)
        @Max(value = 4, message = ProblemTexts.PRIORITY_RANGE)
        Integer priority,

        @Schema(description = "Chỉ ADD_LABELS / REMOVE_LABELS (bắt buộc, không rỗng)")
        List<UUID> labelIds) {
}
