package com.taskly.mapper;

import com.taskly.dto.task.TaskResponse;
import com.taskly.repository.TaskView;
import com.taskly.service.TaskDueRules;
import java.time.LocalDateTime;

public final class TaskMapper {

    private TaskMapper() {
    }

    /** localNow = giờ treo tường theo múi giờ tài khoản — isOverdue là trạng thái suy ra, không lưu (spec 5.1). */
    public static TaskResponse toResponse(TaskView v, LocalDateTime localNow) {
        boolean overdue = TaskDueRules.isOverdue(v.dueDate(), v.dueDatetime(), v.completedAt(), localNow);
        return new TaskResponse(v.id(), v.projectId(), v.sectionId(), v.parentId(), v.content(), v.description(),
                v.priority(), v.dueDate(), v.dueDatetime(), v.durationMinutes(), v.deadlineDate(), v.dueString(),
                overdue, v.completedAt(), v.subtaskCount(), v.completedSubtaskCount(), v.sortOrder(), v.version(),
                v.createdAt(), v.updatedAt(), v.projectName(), v.parentContent(), v.labelIds(), v.recurrenceRule(),
                v.recurrenceFromCompletion(), v.dueTimezone(), v.recurrenceRule() != null,
                v.commentCount(), v.reviewStatus(), v.reviewRequestedBy(), v.reviewRequestedAt());
    }
}
