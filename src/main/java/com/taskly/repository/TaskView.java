package com.taskly.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Một dòng đọc từ TaskViewRepository: cột task + dữ liệu suy ra (tiến độ, tên dự án, tên cha). dueDatetime floating. */
public record TaskView(UUID id, UUID projectId, UUID sectionId, UUID parentId, String content, String description,
                       int priority, LocalDate dueDate, LocalDateTime dueDatetime, Integer durationMinutes,
                       LocalDate deadlineDate, String dueString, Instant completedAt, int subtaskCount,
                       int completedSubtaskCount, long sortOrder, long version, Instant createdAt, Instant updatedAt,
                       String projectName, String parentContent, List<UUID> labelIds, String recurrenceRule,
                       boolean recurrenceFromCompletion, String dueTimezone,
                       int commentCount, String reviewStatus, UUID reviewRequestedBy,
                       Instant reviewRequestedAt) {
}
