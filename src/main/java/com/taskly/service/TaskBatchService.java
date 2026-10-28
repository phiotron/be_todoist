package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ProblemCodes;
import com.taskly.common.error.ErrorMessage;
import com.taskly.dto.task.AffectedTasksResponse;
import com.taskly.dto.task.BatchTasksRequest;
import com.taskly.dto.task.MoveTaskRequest;
import com.taskly.dto.task.RescheduleOverdueRequest;
import com.taskly.entity.ActivityEventType;
import com.taskly.entity.BatchAction;
import com.taskly.entity.Project;
import com.taskly.entity.Task;
import com.taskly.security.AccessPolicy;
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
    private final ProjectService projectService;
    private final TaskLabelService taskLabelService;
    private final Clock clock;
    private final ActivityService activityService; // M10
    private final ProductivityEvents productivityEvents; // M10
    private final AccessPolicy accessPolicy; // PERM

    public TaskBatchService(TaskService taskService, TaskCompletionService taskCompletionService,
                            TaskMoveService taskMoveService, SubtaskTreeService subtaskTreeService,
                            UsageLimits usageLimits, UserService userService, ProjectService projectService,
                            TaskLabelService taskLabelService, Clock clock, ActivityService activityService,
                            ProductivityEvents productivityEvents, AccessPolicy accessPolicy) {
        this.accessPolicy = accessPolicy;
        this.activityService = activityService;
        this.productivityEvents = productivityEvents;
        this.projectService = projectService;
        this.taskService = taskService;
        this.taskCompletionService = taskCompletionService;
        this.taskMoveService = taskMoveService;
        this.subtaskTreeService = subtaskTreeService;
        this.usageLimits = usageLimits;
        this.userService = userService;
        this.taskLabelService = taskLabelService;
        this.clock = clock;
    }

    public AffectedTasksResponse execute(UUID userId, BatchTasksRequest request) {
        validate(request);
        List<UUID> ids = request.taskIds();
        // 404 cả lô nếu một id không thấy được: một truy vấn lấy task, rồi kiểm quyền đọc MỘT lần cho mỗi dự án
        Map<UUID, Task> byId = taskService.loadAll(ids).stream().collect(Collectors.toMap(Task::getId, t -> t));
        List<Task> tasks = new ArrayList<>();
        for (UUID id : ids) {
            Task task = byId.get(id);
            if (task == null) {
                throw ApiException.notFound(ErrorMessage.TASK_NOT_FOUND);
            }
            tasks.add(task);
        }
        for (UUID projectId : tasks.stream().map(Task::getProjectId).distinct().toList()) {
            projectService.requireReadable(userId, projectId, ErrorMessage.TASK_NOT_FOUND);
        }
        // 409 project-archived / section-archived — KỂ CẢ DELETE (spec I15): mỗi (dự án, section) kiểm MỘT lần
        // PERM: CLOSE / REOPEN chỉ đòi đã tham gia (Member / Guest dự án nhóm được); action khác → 403 team-admin-only
        boolean completion = request.action() == BatchAction.CLOSE || request.action() == BatchAction.REOPEN;
        Set<List<UUID>> checked = new HashSet<>();
        for (Task task : tasks) {
            if (checked.add(java.util.Arrays.asList(task.getProjectId(), task.getSectionId()))) {
                if (completion) {
                    taskService.requireCompletable(userId, task.getId());
                } else {
                    taskService.requireWritable(userId, task.getId());
                }
            }
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
            case SCHEDULE -> schedule(userId, tasks, request.date().orElse(null));
            case PRIORITY -> priority(userId, tasks, request.priority());
            case ADD_LABELS -> {
                taskLabelService.addToTasks(userId, ids, request.labelIds());
                yield new LinkedHashSet<>(ids); // mọi taskIds gửi lên, kể cả task không đổi (spec 12.5)
            }
            case REMOVE_LABELS -> {
                taskLabelService.removeFromTasks(userId, ids, request.labelIds());
                yield new LinkedHashSet<>(ids);
            }
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
        List<Task> overdue = taskService.findOverdueLocked(userId);
        // PERM: task quá hạn của dự án nhóm mà user chỉ là Member / Guest → BỎ QUA (không 403 cả lô — quyết định tạm)
        Map<UUID, AccessPolicy.ProjectAccessInfo> access = accessPolicy.projectAccess(userId,
                overdue.stream().map(Task::getProjectId).collect(Collectors.toSet()));
        for (Task task : overdue) {
            AccessPolicy.ProjectAccessInfo info = access.get(task.getProjectId());
            if (info == null || !info.canEdit()) {
                continue;
            }
            reschedule(userId, task, request.date());
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
        lockAndRecheckAll(tasks);
        Set<UUID> affected = new LinkedHashSet<>();
        Instant now = clock.instant();
        UUID batchId = UUID.randomUUID();
        Map<UUID, SubtaskTreeService.Tree> trees = treesByProject(tasks); // dựng MỘT lần cho mỗi dự án nguồn
        Map<UUID, UUID> projectOf = tasks.stream().collect(Collectors.toMap(Task::getId, Task::getProjectId));
        // M6c: task lặp trong lô nhảy kỳ (reset sub-tasks theo cài đặt) thay vì hoàn thành
        rootsWithSubtrees(tasks, trees).keySet().forEach(root ->
                affected.addAll(taskCompletionService.closeOrAdvanceWithin(userId, trees.get(projectOf.get(root)), root,
                        now, batchId, false, null)));
        return affected;
    }

    /**
     * Cổng khoá P chung của CLOSE / SCHEDULE / PRIORITY / DELETE: khoá mọi dự án chứa task theo id TĂNG DẦN rồi đọc lại
     * từng task — vừa xoá → 404, vừa chuyển dự án → 409 version-conflict, dự án / section vừa lưu trữ → 409. Cùng cổng
     * reopen / reorder task nên không deadlock cấp hàng với reorder đánh số lại.
     */
    private void lockAndRecheckAll(List<Task> tasks) {
        Set<UUID> toLock = tasks.stream().map(Task::getProjectId).collect(Collectors.toCollection(HashSet::new));
        usageLimits.lockProjects(toLock);
        for (Task task : tasks) {
            taskService.refresh(task);
            if (!toLock.contains(task.getProjectId())) {
                throw ApiException.conflict(ErrorMessage.VERSION_CONFLICT).withCode(ProblemCodes.VERSION_CONFLICT);
            }
        }
        toLock.forEach(projectService::recheckWritableAfterLock);
        tasks.stream().map(Task::getSectionId).filter(java.util.Objects::nonNull).distinct().forEach(sectionId -> {
            if (taskService.isSectionArchivedNow(sectionId)) {
                throw SectionService.sectionArchived();
            }
        });
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
        // Khoá TRƯỚC mọi dự án nguồn + đích theo id TĂNG DẦN (như reopen): TaskMoveService.move chỉ khoá đúng thứ tự trong
        // MỘT lần gọi, còn nhiều gốc gọi nối tiếp trong cùng transaction thì không → deadlock với move / archive khác.
        Project target = projectService.requireTarget(userId, request.projectId()); // 404 / 422 như move từng gốc
        Set<UUID> toLock = tasks.stream().map(Task::getProjectId).collect(Collectors.toCollection(HashSet::new));
        toLock.add(target.getId());
        usageLimits.lockProjects(toLock);
        // Tập khoá tính từ projectId nạp TRƯỚC khoá: task có thể vừa bị chuyển sang dự án ngoài tập (khoá sau → phá thứ
        // tự tăng dần, và move âm thầm lấy task từ dự án không được khoá). Đọc lại sau khoá; lệch → 409, cả lô rollback.
        for (Task task : tasks) {
            taskService.refresh(task);
            if (!toLock.contains(task.getProjectId())) {
                throw ApiException.conflict(ErrorMessage.VERSION_CONFLICT).withCode(ProblemCodes.VERSION_CONFLICT);
            }
        }
        Set<UUID> affected = new LinkedHashSet<>();
        // rootsWithSubtrees tính TRƯỚC khi đổi gì; thứ tự gốc = thứ tự trong taskIds → thêm vào cuối đích đúng thứ tự.
        rootsWithSubtrees(tasks, treesByProject(tasks)).forEach((root, descendants) -> {
            affected.add(root);
            affected.addAll(descendants);
            taskMoveService.move(userId, root, new MoveTaskRequest(request.projectId(), request.sectionId(), null, null));
        });
        return affected;
    }

    private Set<UUID> delete(UUID userId, List<Task> tasks) {
        lockAndRecheckAll(tasks);
        Set<UUID> affected = new LinkedHashSet<>();
        rootsWithSubtrees(tasks, treesByProject(tasks)).forEach((root, descendants) -> { // tính TRƯỚC khi xoá
            affected.add(root);
            affected.addAll(descendants);
            taskService.delete(userId, root);
        });
        return affected;
    }

    private Set<UUID> schedule(UUID userId, List<Task> tasks, LocalDate date) {
        lockAndRecheckAll(tasks);
        if (tasks.stream().anyMatch(task -> task.getCompletedAt() != null)) { // vừa hoàn thành trong lúc chờ khoá
            throw ApiException.conflict(ErrorMessage.TASK_COMPLETED).withCode(ProblemCodes.TASK_COMPLETED);
        }
        Set<UUID> affected = new LinkedHashSet<>();
        for (Task task : tasks) {
            reschedule(userId, task, date);
            affected.add(task.getId());
        }
        return affected;
    }

    private Set<UUID> priority(UUID userId, List<Task> tasks, int priority) {
        lockAndRecheckAll(tasks);
        Set<UUID> affected = new LinkedHashSet<>();
        for (Task task : tasks) {
            boolean changed = task.getPriority() != priority;
            task.setPriority(priority);
            if (changed) {
                activityService.task(userId, task, ActivityEventType.UPDATED); // M10
            }
            affected.add(task.getId());
        }
        return affected;
    }

    private void reschedule(UUID userId, Task task, LocalDate date) {
        List<Object> tracked = ActivityService.trackedFields(task); // M10
        TaskDueRules.Due due = TaskDueRules.reschedule(task.getDueDate(), task.getDueDatetime(), date);
        Integer duration = due.datetime() == null ? null : task.getDurationMinutes(); // mất giờ → xoá duration
        taskService.applyDue(task, due.date(), due.datetime(), duration);
        if (!tracked.equals(ActivityService.trackedFields(task))) {
            activityService.task(userId, task, ActivityEventType.UPDATED); // M10
        }
    }

    // ---- trợ giúp cây ----

    /** Cây công việc của từng dự án nguồn, dựng MỘT lần cho mỗi dự án. */
    private Map<UUID, SubtaskTreeService.Tree> treesByProject(List<Task> tasks) {
        Map<UUID, SubtaskTreeService.Tree> treeByProject = new HashMap<>();
        for (Task task : tasks) {
            treeByProject.computeIfAbsent(task.getProjectId(), taskService::tree);
        }
        return treeByProject;
    }

    /**
     * Các task GỐC của lô (bỏ task có tổ tiên cũng trong lô — cây con đi theo tổ tiên) → con cháu của nó theo cây
     * (treesByProject) hiện tại; giữ thứ tự xuất hiện trong taskIds.
     */
    private Map<UUID, List<UUID>> rootsWithSubtrees(List<Task> tasks, Map<UUID, SubtaskTreeService.Tree> treeByProject) {
        Map<UUID, List<UUID>> idsByProject = new LinkedHashMap<>();
        for (Task task : tasks) {
            idsByProject.computeIfAbsent(task.getProjectId(), key -> new ArrayList<>()).add(task.getId());
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
        boolean labelAction = action == BatchAction.ADD_LABELS || action == BatchAction.REMOVE_LABELS;
        boolean hasLabels = request.labelIds() != null && !request.labelIds().isEmpty();
        if (labelAction != hasLabels || (!labelAction && request.labelIds() != null)) {
            throw ApiException.badRequest(ErrorMessage.BATCH_LABEL_IDS);
        }
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
