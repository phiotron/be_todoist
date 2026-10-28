package com.taskly.service;

import com.taskly.dto.task.CascadedTask;
import com.taskly.dto.task.TaskChangeResponse;
import com.taskly.dto.task.TaskResponse;
import com.taskly.entity.Task;
import com.taskly.service.recurrence.InvalidRecurrenceException;
import com.taskly.service.recurrence.RecurrenceRule;
import com.taskly.service.recurrence.RecurrenceService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hoàn thành / mở lại / Undo có lan truyền (spec 5.1, 5.4, 5.6) trong MỘT transaction. Lặp lại thao tác trả trạng
 * thái hiện tại, không ghi đè completed_at / completion_batch_id (BR-05). closeAt / reopenIds công khai cho
 * TaskBatchService (tasks/batch). Mọi task đổi (kể cả task lan truyền) đổi qua entity nên tăng version.
 *
 * <p>M6c (spec 5.3, contract 8.1): task LẶP hoàn thành một lần → KHÔNG đặt completed_at, KHÔNG lan truyền con; CHÍNH
 * task nhảy sang kỳ kế (RecurrenceService, "hôm nay" theo múi giờ tài khoản), giữ giờ / duration / deadline /
 * due_timezone; lưu ảnh chụp Undo (due cũ). `forever` hoặc hết chu kỳ (UNTIL) → hoàn thành thật như M1.
 * "Reset sub-tasks" mở lại tối đa 30 con TRỰC TIẾP đã xong (cấp 3 trở xuống không đụng). Undo (`reopen {undo:true}`)
 * trên task lặp còn ảnh chụp → due cũ quay lại, con vừa reset đóng lại.
 */
@Service
@Transactional
public class TaskCompletionService {

    /** Todoist: "Complete & reset sub-tasks" chỉ áp cho tối đa 30 task con (plan M6c QT 5). */
    public static final int MAX_RESET_SUBTASKS = 30;

    private final TaskService taskService;
    private final SubtaskTreeService subtaskTreeService;
    private final UserService userService;
    private final RecurrenceService recurrenceService = new RecurrenceService();
    private final Clock clock;
    private final ProductivityEvents productivityEvents; // M10: Activity log + Karma

    public TaskCompletionService(TaskService taskService, SubtaskTreeService subtaskTreeService, UserService userService,
                                 Clock clock, ProductivityEvents productivityEvents) {
        this.taskService = taskService;
        this.subtaskTreeService = subtaskTreeService;
        this.userService = userService;
        this.clock = clock;
        this.productivityEvents = productivityEvents;
    }

    /** Mỗi lần gọi sinh MỘT completion_batch_id mới (căn cứ của Undo, spec 5.1). */
    public TaskChangeResponse close(UUID userId, UUID taskId) {
        return close(userId, taskId, false, null);
    }

    /**
     * M6c: `forever` = kết thúc chu kỳ (hoàn thành thật); `resetSubtasks` null = theo me/settings.resetSubtasks. Hai cờ
     * chỉ có nghĩa với task lặp.
     */
    public TaskChangeResponse close(UUID userId, UUID taskId, boolean forever, Boolean resetSubtasks) {
        return response(userId, taskId, closeAt(userId, taskId, clock.instant(), UUID.randomUUID(), forever, resetSubtasks));
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
        return closeAt(userId, taskId, now, batchId, false, null);
    }

    private List<UUID> closeAt(UUID userId, UUID taskId, Instant now, UUID batchId, boolean forever,
                               Boolean resetSubtasks) {
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
        return closeOrAdvanceWithin(userId, taskService.tree(task.getProjectId()), taskId, now, batchId, forever,
                resetSubtasks);
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
     * M6c: như closeWithin, nhưng task LẶP (không forever, còn kỳ kế) thì nhảy kỳ thay vì hoàn thành. tasks/batch CLOSE
     * dùng hàm này với resetSubtasks = null (theo cài đặt của người dùng).
     */
    public List<UUID> closeOrAdvanceWithin(UUID userId, SubtaskTreeService.Tree tree, UUID taskId, Instant now,
                                           UUID batchId, boolean forever, Boolean resetSubtasks) {
        if (tree.node(taskId).completed()) {
            return List.of();
        }
        if (!forever) {
            Task task = taskService.reload(taskId);
            if (task.isRecurring()) {
                Optional<List<UUID>> advanced = advance(userId, task, tree, now, batchId, resetSubtasks);
                if (advanced.isPresent()) {
                    // M10: task lặp nhảy kỳ vẫn tính là MỘT lần hoàn thành (Activity + Karma); con bị reset thì không
                    productivityEvents.tasksCompleted(userId, List.of(taskId));
                    return advanced.get();
                }
            }
        }
        List<UUID> changed = closeWithin(tree, taskId, now, batchId);
        productivityEvents.tasksCompleted(userId, changed); // M10: gồm close forever / hết chu kỳ
        return changed;
    }

    /**
     * Nhảy task lặp sang kỳ kế. Rỗng = hết chu kỳ (hoặc rule hỏng) → caller hoàn thành thật. Reset con làm tăng số task
     * đang mở → kiểm giới hạn 300 (dự án đã khoá) TRƯỚC khi ghi.
     */
    private Optional<List<UUID>> advance(UUID userId, Task task, SubtaskTreeService.Tree tree, Instant now, UUID batchId,
                                         Boolean resetSubtasks) {
        if (task.getDueDate() == null && task.getDueDatetime() == null) {
            return Optional.empty();
        }
        RecurrenceRule rule;
        try {
            rule = RecurrenceRule.parse(task.getRecurrenceRule());
        } catch (InvalidRecurrenceException e) {
            return Optional.empty();
        }
        Optional<RecurrenceService.Due> next = recurrenceService.next(rule, task.isRecurrenceFromCompletion(),
                RecurrenceService.Due.of(task.getDueDate(), task.getDueDatetime()), now, userService.timeZoneOf(userId));
        if (next.isEmpty()) {
            return Optional.empty();
        }
        boolean reset = resetSubtasks != null ? resetSubtasks : userService.requireUser(userId).isResetSubtasks();
        List<UUID> resetIds = reset
                ? subtaskTreeService.completedChildren(tree, task.getId(), MAX_RESET_SUBTASKS) : List.of();
        taskService.checkActiveTaskLimit(task.getProjectId(), resetIds.size());
        RecurrenceService.Due due = next.get();
        task.advanceRecurrence(due.time() == null ? due.date() : null, due.toDatetime(), batchId);
        taskService.loadAll(resetIds).forEach(child -> child.resetForRecurrence(batchId));
        List<UUID> changed = new ArrayList<>();
        changed.add(task.getId());
        changed.addAll(resetIds);
        return Optional.of(changed);
    }

    /**
     * Mở lại: thường → task + tổ tiên đang xong (Task.reopen xoá completion_batch_id); undo → thêm con cháu có CÙNG
     * completion_batch_id với task (SubtaskTreeService.idsToUndo; batch null → chỉ task + tổ tiên). Task đang mở →
     * rỗng — trừ M6c: undo trên task lặp còn ảnh chụp → trả due cũ (undoRecurrence). Mở lại làm tăng số task đang mở
     * → khoá dự án rồi kiểm giới hạn 300 (TaskService.checkActiveTaskLimit) TRƯỚC khi ghi.
     */
    public List<UUID> reopenIds(UUID userId, UUID taskId, boolean undo) {
        Task task = taskService.requireWritable(userId, taskId);
        if (task.getCompletedAt() == null && !(undo && task.getRecurrenceUndoBatchId() != null)) {
            return List.of();
        }
        task = taskService.lockAndRecheck(task); // khoá P rồi đọc lại: dự án / section vừa lưu trữ → 409, task vừa mở / chuyển
        if (task.getCompletedAt() == null) {
            return undo ? undoRecurrence(task) : List.of();
        }
        SubtaskTreeService.Tree tree = taskService.tree(task.getProjectId());
        List<UUID> changed = undo ? subtaskTreeService.idsToUndo(tree, taskId) : subtaskTreeService.idsToReopen(tree, taskId);
        taskService.checkActiveTaskLimit(task.getProjectId(), changed.size());
        taskService.loadAll(changed).forEach(Task::reopen);
        productivityEvents.tasksReopened(userId, changed); // M10
        return changed;
    }

    /** Undo hoàn thành task lặp: due cũ; con trực tiếp vừa reset (cùng batch) còn đang mở → hoàn thành lại. */
    private List<UUID> undoRecurrence(Task task) {
        UUID batchId = task.getRecurrenceUndoBatchId();
        if (!task.undoRecurrence()) {
            return List.of();
        }
        List<UUID> changed = new ArrayList<>();
        changed.add(task.getId());
        List<UUID> children = taskService.tree(task.getProjectId()).childrenOf(task.getId()).stream()
                .map(SubtaskTreeService.Node::id).toList();
        for (Task child : taskService.loadAll(children)) {
            if (child.undoReset(batchId)) {
                changed.add(child.getId());
            }
        }
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
