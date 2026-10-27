package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ProblemCodes;
import com.taskly.common.error.ErrorMessage;
import com.taskly.dto.task.AffectedTasksResponse;
import com.taskly.dto.task.BatchTasksRequest;
import com.taskly.dto.task.MoveTaskRequest;
import com.taskly.dto.task.RescheduleOverdueRequest;
import com.taskly.entity.BatchAction;
import com.taskly.entity.Task;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chọn nhiều (spec 12.5) và Reschedule quá hạn. TẤT CẢ HOẶC KHÔNG: một transaction; kiểm trường → 404 cho id không
 * thấy → 409 (dự án / section lưu trữ — KỂ CẢ DELETE, spec I15; SCHEDULE / MOVE có task đã xong → 409 task-completed)
 * → mới đổi dữ liệu; lỗi giữa chừng (422 đích lưu trữ, vượt giới hạn...) rollback cả lô. Không cần version (task đổi
 * nội dung vẫn tăng version qua entity). Nhận Idempotency-Key (IdempotencyFilter.COVERED, Part 2 Task 16): CLOSE /
 * REOPEN / SCHEDULE / PRIORITY tự idempotent, MOVE / DELETE KHÔNG idempotent nếu thiếu key (app luôn gửi key).
 * CLOSE: MỘT completion_batch_id chung cho cả request, id có tổ tiên cũng trong danh sách bị bỏ qua (spec 5.1).
 * Ghi lại bằng các service có sẵn (TaskCompletionService, TaskMoveService, TaskService) để không lặp quy tắc.
 */
@Service
@Transactional
public class TaskBatchService {

    private final TaskService taskService;
    private final TaskCompletionService taskCompletionService;
    private final TaskMoveService taskMoveService;
    private final SubtaskTreeService subtaskTreeService;
    private final UsageLimits usageLimits;
    private final UserService userService;
    private final Clock clock;

    public TaskBatchService(TaskService taskService, TaskCompletionService taskCompletionService,
                            TaskMoveService taskMoveService, SubtaskTreeService subtaskTreeService,
                            UsageLimits usageLimits, UserService userService, Clock clock) {
        this.taskService = taskService;
        this.taskCompletionService = taskCompletionService;
        this.taskMoveService = taskMoveService;
        this.subtaskTreeService = subtaskTreeService;
        this.usageLimits = usageLimits;
        this.userService = userService;
        this.clock = clock;
    }

    public AffectedTasksResponse execute(UUID userId, BatchTasksRequest request) {
        validate(request);
        List<UUID> ids = request.taskIds();
        List<Task> tasks = new ArrayList<>();
        for (UUID id : ids) {
            tasks.add(taskService.requireReadable(userId, id)); // 404 cả lô nếu một id không thấy được
        }
        for (UUID id : ids) {
            taskService.requireWritable(userId, id); // 409 project-archived / section-archived — KỂ CẢ DELETE (spec I15)
        }
        if ((request.action() == BatchAction.SCHEDULE || request.action() == BatchAction.MOVE)
                && tasks.stream().anyMatch(task -> task.getCompletedAt() != null)) {
            throw ApiException.conflict(ErrorMessage.TASK_COMPLETED).withCode(ProblemCodes.TASK_COMPLETED); // spec 12.5, m3
        }
        Set<UUID> affected = switch (request.action()) {
            case CLOSE -> close(userId, tasks);
            case REOPEN -> reopen(userId, tasks, Boolean.TRUE.equals(request.undo()));
            case MOVE -> move(userId, tasks, request);
            case DELETE -> delete(userId, tasks);
            case SCHEDULE -> schedule(tasks, request.date().orElse(null));
            case PRIORITY -> priority(tasks, request.priority());
        };
        return new AffectedTasksResponse(List.copyOf(affected));
    }

    /**
     * Dời mọi task trong khối Quá hạn (due_day < hôm nay) sang date; date phải ≥ hôm nay theo múi giờ tài khoản (sớm
     * hơn → 400); task có giờ giữ giờ + duration (spec 12.5). Mỗi task đổi tăng version.
     */
    public AffectedTasksResponse rescheduleOverdue(UUID userId, RescheduleOverdueRequest request) {
        LocalDate today = TaskDueRules.today(clock.instant(), userService.timeZoneOf(userId));
        if (request.date().isBefore(today)) {
            throw ApiException.badRequest(ErrorMessage.RESCHEDULE_DATE_PAST);
        }
        Set<UUID> affected = new LinkedHashSet<>();
        for (Task task : taskService.findOverdue(userId)) {
            reschedule(task, request.date());
            affected.add(task.getId());
        }
        return new AffectedTasksResponse(List.copyOf(affected));
    }

    // ---- từng action ----

    /**
     * Cùng một completed_at VÀ cùng MỘT completion_batch_id cho cả lô (căn cứ Undo — spec 5.1). Chỉ gọi closeAt cho
     * task GỐC của lô (id có tổ tiên cũng trong danh sách bị bỏ qua: cha kéo con theo).
     */
    private Set<UUID> close(UUID userId, List<Task> tasks) {
        Set<UUID> affected = new LinkedHashSet<>();
        Instant now = clock.instant();
        UUID batchId = UUID.randomUUID();
        rootsWithSubtrees(tasks).keySet().forEach(root -> affected.addAll(taskCompletionService.closeAt(userId, root, now, batchId)));
        return affected;
    }

    /**
     * Mở lại làm tăng số task đang mở của từng dự án → KHOÁ mọi dự án liên quan theo id TĂNG DẦN trước (spec 8.2 #9,
     * tránh deadlock giữa hai request chọn nhiều), rồi mới mở lại từng id (mỗi id tự kiểm giới hạn 300).
     */
    private Set<UUID> reopen(UUID userId, List<Task> tasks, boolean undo) {
        usageLimits.lockProjects(tasks.stream().map(Task::getProjectId).collect(Collectors.toSet()));
        Set<UUID> affected = new LinkedHashSet<>();
        tasks.forEach(task -> affected.addAll(taskCompletionService.reopenIds(userId, task.getId(), undo)));
        return affected;
    }

    /** Bỏ id có tổ tiên cũng trong danh sách; mỗi task thành task GỐC ở đích, thêm vào cuối theo thứ tự taskIds. */
    private Set<UUID> move(UUID userId, List<Task> tasks, BatchTasksRequest request) {
        Set<UUID> affected = new LinkedHashSet<>();
        // rootsWithSubtrees tính TRƯỚC khi đổi gì; thứ tự gốc = thứ tự trong taskIds → thêm vào cuối đích đúng thứ tự.
        rootsWithSubtrees(tasks).forEach((root, descendants) -> {
            affected.add(root);
            affected.addAll(descendants);
            taskMoveService.move(userId, root, new MoveTaskRequest(request.projectId(), request.sectionId(), null, null));
        });
        return affected;
    }

    private Set<UUID> delete(UUID userId, List<Task> tasks) {
        Set<UUID> affected = new LinkedHashSet<>();
        rootsWithSubtrees(tasks).forEach((root, descendants) -> { // tính TRƯỚC khi xoá
            affected.add(root);
            affected.addAll(descendants);
            taskService.delete(userId, root);
        });
        return affected;
    }

    private Set<UUID> schedule(List<Task> tasks, LocalDate date) {
        Set<UUID> affected = new LinkedHashSet<>();
        for (Task task : tasks) {
            reschedule(task, date);
            affected.add(task.getId());
        }
        return affected;
    }

    private Set<UUID> priority(List<Task> tasks, int priority) {
        Set<UUID> affected = new LinkedHashSet<>();
        for (Task task : tasks) {
            task.setPriority(priority);
            affected.add(task.getId());
        }
        return affected;
    }

    private void reschedule(Task task, LocalDate date) {
        TaskDueRules.Due due = TaskDueRules.reschedule(task.getDueDate(), task.getDueDatetime(), date);
        Integer duration = due.datetime() == null ? null : task.getDurationMinutes(); // mất giờ → xoá duration
        taskService.applyDue(task, due.date(), due.datetime(), duration);
    }

    // ---- trợ giúp cây ----

    /**
     * Các task GỐC của lô (bỏ task có tổ tiên cũng trong lô — cây con đi theo tổ tiên) → con cháu của nó theo cây hiện
     * tại; giữ thứ tự xuất hiện trong taskIds. Dựng cây một lần cho mỗi dự án nguồn.
     */
    private Map<UUID, List<UUID>> rootsWithSubtrees(List<Task> tasks) {
        Map<UUID, SubtaskTreeService.Tree> treeByProject = new HashMap<>();
        Map<UUID, List<UUID>> idsByProject = new LinkedHashMap<>();
        for (Task task : tasks) {
            idsByProject.computeIfAbsent(task.getProjectId(), key -> new ArrayList<>()).add(task.getId());
            treeByProject.computeIfAbsent(task.getProjectId(), taskService::tree);
        }
        Set<UUID> roots = new HashSet<>();
        idsByProject.forEach((projectId, ids) ->
                roots.addAll(subtaskTreeService.withoutDescendants(treeByProject.get(projectId), ids)));
        Map<UUID, List<UUID>> result = new LinkedHashMap<>();
        for (Task task : tasks) {
            if (roots.contains(task.getId())) {
                result.put(task.getId(),
                        subtaskTreeService.descendants(treeByProject.get(task.getProjectId()), task.getId()));
            }
        }
        return result;
    }

    // ---- kiểm trường theo action (Q36) ----

    static void validate(BatchTasksRequest request) {
        if (new HashSet<>(request.taskIds()).size() != request.taskIds().size()) {
            throw ApiException.badRequest(ErrorMessage.BATCH_IDS_DUPLICATE);
        }
        BatchAction action = request.action();
        boolean extra = (request.undo() != null && action != BatchAction.REOPEN)
                || ((request.projectId() != null || request.sectionId() != null) && action != BatchAction.MOVE)
                || (request.date() != null && action != BatchAction.SCHEDULE)
                || (request.priority() != null && action != BatchAction.PRIORITY);
        boolean missing = (action == BatchAction.MOVE && request.projectId() == null)
                || (action == BatchAction.SCHEDULE && request.date() == null)
                || (action == BatchAction.PRIORITY && request.priority() == null);
        if (extra || missing) {
            throw ApiException.badRequest(ErrorMessage.BATCH_FIELDS_INVALID);
        }
    }
}
