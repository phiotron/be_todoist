package com.taskly.service.sync.handler;

import com.taskly.dto.reorder.ReorderRequest;
import com.taskly.dto.task.BatchTasksRequest;
import com.taskly.dto.task.RescheduleOverdueRequest;
import com.taskly.service.ReorderService;
import com.taskly.service.TaskBatchService;
import com.taskly.service.sync.SyncCommandHandler;
import com.taskly.service.sync.SyncCommandOutcome;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Lệnh đồng bộ của thao tác "sắp xếp / chọn nhiều" (M9 giai đoạn 2): args = body REST tương ứng, gọi đúng service nên
 * quyền (AccessPolicy), giới hạn (UsageLimits), khoá chống đua và mã lỗi y như REST.
 * - reorder: POST reorder {type, id, afterId} (afterId BẮT BUỘC có khoá, null = lên đầu; id tạm của bản ghi tạo trước đó
 *   trong cùng request được thay bằng id thật).
 * - task_batch: POST tasks/batch {action, taskIds, ...}; mọi action (CLOSE / REOPEN / MOVE / DELETE / SCHEDULE / PRIORITY /
 *   ADD_LABELS / REMOVE_LABELS) trong MỘT loại lệnh, cả lô là một lệnh (all-or-nothing như REST); uuid của lệnh thay
 *   Idempotency-Key.
 * - task_reschedule_overdue: POST tasks/overdue/reschedule {date}.
 */
@Configuration(proxyBeanMethods = false)
public class OrganizeSyncCommands {

    @Bean
    SyncCommandHandler reorderCommand(ReorderService reorders) {
        return SyncCommandHandler.of("reorder", ctx -> {
            reorders.reorder(ctx.userId(), ctx.body(ReorderRequest.class));
            return SyncCommandOutcome.ok();
        });
    }

    @Bean
    SyncCommandHandler taskBatchCommand(TaskBatchService batches) {
        return SyncCommandHandler.of("task_batch", ctx -> {
            batches.execute(ctx.userId(), ctx.body(BatchTasksRequest.class));
            return SyncCommandOutcome.ok();
        });
    }

    @Bean
    SyncCommandHandler taskRescheduleOverdueCommand(TaskBatchService batches) {
        return SyncCommandHandler.of("task_reschedule_overdue", ctx -> {
            batches.rescheduleOverdue(ctx.userId(), ctx.body(RescheduleOverdueRequest.class));
            return SyncCommandOutcome.ok();
        });
    }
}
