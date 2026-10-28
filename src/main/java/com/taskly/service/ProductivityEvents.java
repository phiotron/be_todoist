package com.taskly.service;

import com.taskly.entity.ActivityEventType;
import java.util.Collection;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Điểm móc DUY NHẤT của M10 vào hoàn thành / mở lại task (TaskCompletionService, TaskBatchService): ghi Activity log và
 * Karma trong cùng transaction. M6c (task lặp) gọi cùng hàm khi gộp để task lặp cũng được tính.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ProductivityEvents {

    private final ActivityService activityService;
    private final KarmaService karmaService;

    public ProductivityEvents(ActivityService activityService, KarmaService karmaService) {
        this.activityService = activityService;
        this.karmaService = karmaService;
    }

    /** taskIds = các task VỪA chuyển sang đã xong (gồm con bị hoàn thành theo); rỗng → không ghi gì. */
    public void tasksCompleted(UUID userId, Collection<UUID> taskIds) {
        activityService.tasks(userId, taskIds, ActivityEventType.COMPLETED);
        karmaService.tasksCompleted(userId, taskIds);
    }

    /** taskIds = các task VỪA mở lại. */
    public void tasksReopened(UUID userId, Collection<UUID> taskIds) {
        activityService.tasks(userId, taskIds, ActivityEventType.UNCOMPLETED);
        karmaService.tasksReopened(taskIds);
    }
}
