package com.taskly.service;

import com.taskly.dto.task.CascadedTask;
import com.taskly.dto.task.TaskChangeResponse;
import com.taskly.dto.task.TaskResponse;
import com.taskly.entity.Task;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hoàn thành / mở lại / Undo có lan truyền (spec 5.1, 5.4, 5.6) trong MỘT transaction. Lặp lại thao tác trả trạng
 * thái hiện tại, không ghi đè completed_at / completion_batch_id (BR-05). closeAt / reopenIds công khai cho
 * TaskBatchService (tasks/batch). Mọi task đổi (kể cả task lan truyền) đổi qua entity nên tăng version.
 */
@Service
@Transactional
public class TaskCompletionService {

    private final TaskService taskService;
    private final SubtaskTreeService subtaskTreeService;
    private final Clock clock;
    private final ProductivityEvents productivityEvents; // M10: Activity log + Karma

    public TaskCompletionService(TaskService taskService, SubtaskTreeService subtaskTreeService, Clock clock,
                                 ProductivityEvents productivityEvents) {
        this.taskService = taskService;
        this.subtaskTreeService = subtaskTreeService;
        this.clock = clock;
        this.productivityEvents = productivityEvents;
    }

    /** Mỗi lần gọi sinh MỘT completion_batch_id mới (căn cứ của Undo, spec 5.1). */
    public TaskChangeResponse close(UUID userId, UUID taskId) {
        return response(userId, taskId, closeAt(userId, taskId, clock.instant(), UUID.randomUUID()));
    }

    public TaskChangeResponse reopen(UUID userId, UUID taskId, boolean undo) {
        return response(userId, taskId, reopenIds(userId, taskId, undo));
    }

    /**
     * Hoàn thành task + mọi con cháu đang mở với CÙNG completed_at = now và CÙNG completion_batch_id = batchId. Task
     * đã xong → rỗng (không đổi gì). Con cháu đã xong từ trước giữ nguyên completed_at / batch cũ. Trả id các task
     * ĐÃ ĐỔI (gồm chính task nếu nó đổi). tasks/batch CLOSE gọi nhiều lần với MỘT batchId chung (spec 5.1).
     */
    public List<UUID> closeAt(UUID userId, UUID taskId, Instant now, UUID batchId) {
        Task task = taskService.requireWritable(userId, taskId);
        if (task.getCompletedAt() != null) {
            return List.of();
        }
        // khoá P rồi đọc lại (cùng cổng reopen / reorder task): ghi hàng task cha + con cháu theo thứ tự khoá thống nhất
        // → không deadlock cấp hàng với reorder đánh số lại; dự án / section vừa lưu trữ → 409
        task = taskService.lockAndRecheck(task);
        if (task.getCompletedAt() != null) {
            return List.of();
        }
        List<UUID> changed = closeWithin(taskService.tree(task.getProjectId()), taskId, now, batchId);
        productivityEvents.tasksCompleted(userId, changed); // M10
        return changed;
    }

    /**
     * Như closeAt nhưng caller ĐÃ kiểm quyền ghi và đã dựng cây dự án (tasks/batch CLOSE: một cây cho cả lô thay vì
     * đọc lại cây + kiểm quyền cho từng task gốc). Cây con của các task gốc trong lô rời nhau nên cây dựng trước khi
     * đóng vẫn đúng cho từng gốc.
     */
    public List<UUID> closeWithin(SubtaskTreeService.Tree tree, UUID taskId, Instant now, UUID batchId) {
        if (tree.node(taskId).completed()) {
            return List.of();
        }
        List<UUID> changed = subtaskTreeService.idsToClose(tree, taskId);
        taskService.loadAll(changed).forEach(t -> t.complete(now, batchId));
        return changed;
    }

    /**
     * Mở lại: thường → task + tổ tiên đang xong (Task.reopen xoá completion_batch_id); undo → thêm con cháu có CÙNG
     * completion_batch_id với task (SubtaskTreeService.idsToUndo; batch null → chỉ task + tổ tiên). Task đang mở →
     * rỗng. Mở lại làm tăng số task đang mở → khoá dự án rồi kiểm giới hạn 300 (TaskService.checkActiveTaskLimit)
     * TRƯỚC khi ghi.
     */
    public List<UUID> reopenIds(UUID userId, UUID taskId, boolean undo) {
        Task task = taskService.requireWritable(userId, taskId);
        if (task.getCompletedAt() == null) {
            return List.of();
        }
        task = taskService.lockAndRecheck(task); // khoá P rồi đọc lại: dự án / section vừa lưu trữ → 409, task vừa mở / chuyển
        if (task.getCompletedAt() == null) {
            return List.of();
        }
        SubtaskTreeService.Tree tree = taskService.tree(task.getProjectId());
        List<UUID> changed = undo ? subtaskTreeService.idsToUndo(tree, taskId) : subtaskTreeService.idsToReopen(tree, taskId);
        taskService.checkActiveTaskLimit(task.getProjectId(), changed.size());
        taskService.loadAll(changed).forEach(Task::reopen);
        productivityEvents.tasksReopened(userId, changed); // M10
        return changed;
    }

    /**
     * task (đọc lại bằng SQL sau flush) + `cascaded` = các task KHÁC đã đổi, kèm version / completedAt mới. Sau
     * toResponse (flush) các entity vẫn được quản lý nên getVersion() đã là giá trị mới.
     */
    private TaskChangeResponse response(UUID userId, UUID taskId, List<UUID> changed) {
        TaskResponse task = taskService.toResponse(userId, taskId);
        List<UUID> others = changed.stream().filter(id -> !id.equals(taskId)).toList();
        Map<UUID, Task> byId = taskService.loadAll(others).stream()
                .collect(Collectors.toMap(Task::getId, Function.identity()));
        List<CascadedTask> cascaded = others.stream()
                .map(byId::get)
                .map(t -> new CascadedTask(t.getId(), t.getVersion(), t.getCompletedAt()))
                .toList();
        return new TaskChangeResponse(task, cascaded);
    }
}
