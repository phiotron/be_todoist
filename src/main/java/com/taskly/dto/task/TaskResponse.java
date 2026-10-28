package com.taskly.dto.task;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Spec 12.3. Mọi field luôn có mặt; field có thể null đánh nullable. projectName/parentContent có ở mọi endpoint
 * (Q16). KHÔNG có isFavorite (bản 3). M2 thêm labelIds; M6 thêm isRecurring; M8 thêm assigneeId, commentCount.
 */
public record TaskResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID projectId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) UUID sectionId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) UUID parentId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String content,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String description,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "1 = P1 (cao nhất) … 4 = P4") int priority,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) LocalDate dueDate,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, type = "string",
                example = "2026-10-25T09:00:00", description = "Giờ floating (giờ địa phương, KHÔNG có Z / offset)")
        LocalDateTime dueDatetime,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, description = "1–1440, chỉ khi có giờ")
        Integer durationMinutes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) LocalDate deadlineDate,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, description = "M6; M1 luôn null")
        String dueString,
        @JsonProperty("isOverdue") @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isOverdue,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Instant completedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int subtaskCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int completedSubtaskCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long sortOrder,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long version,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant updatedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String projectName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String parentContent,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "M2: label của task, theo thứ tự label")
        List<UUID> labelIds,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "M8b: số comment của task")
        int commentCount) {
}
