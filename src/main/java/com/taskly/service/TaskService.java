package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ProblemCodes;
import com.taskly.common.error.ProblemTexts;
import com.taskly.dto.common.Created;
import com.taskly.dto.task.CreateTaskRequest;
import com.taskly.dto.task.TaskResponse;
import com.taskly.dto.task.UpdateTaskRequest;
import com.taskly.entity.LimitKind;
import com.taskly.entity.Project;
import com.taskly.entity.Task;
import com.taskly.mapper.TaskMapper;
import com.taskly.repository.TaskRepository;
import com.taskly.repository.TaskView;
import com.taskly.repository.TaskViewRepository;
import com.taskly.security.AccessPolicy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sở hữu bảng tasks: tạo / sửa / xoá vĩnh viễn, và các phép dùng chung cho TaskCompletion/Move/Duplicate/Batch/
 * Reorder/View (tra cứu có kiểm quyền, vùng chứa sắp xếp, cây công việc con, giới hạn 300, dựng TaskResponse).
 *
 * <p>QUY TẮC GHI (spec 5.6, 8.2 #4 #11): nội dung task đổi qua ENTITY (@Version → version + 1). MỌI thay đổi
 * sort_order đi qua writeSortOrder / writeSortOrders (native UPDATE: không tăng version, vẫn ghi updated_at) —
 * KHÔNG có Task.setSortOrder (cột sort_order updatable = false; chỉ constructor lúc tạo và native UPDATE ghi).
 * Mỗi native UPDATE flush rồi XOÁ persistence context (clearAutomatically): entity đã nạp trước đó thành
 * detached — sau lời gọi native chỉ ĐỌC chúng; cần sửa thì reload(id) rồi mới đổi.
 */
@Service
@Transactional
public class TaskService {

    @PersistenceContext
    private EntityManager entityManager;

    private final TaskRepository taskRepository;
    private final TaskViewRepository taskViewRepository;
    private final ProjectService projectService;
    private final SectionService sectionService;
    private final UserService userService;
    private final AccessPolicy accessPolicy;
    private final SortOrderService sortOrderService;
    private final SubtaskTreeService subtaskTreeService;
    private final UsageLimits usageLimits;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public TaskService(TaskRepository taskRepository, TaskViewRepository taskViewRepository,
                       ProjectService projectService, SectionService sectionService, UserService userService,
                       AccessPolicy accessPolicy, SortOrderService sortOrderService,
                       SubtaskTreeService subtaskTreeService, UsageLimits usageLimits,
                       ApplicationEventPublisher events, Clock clock) {
        this.taskRepository = taskRepository;
        this.taskViewRepository = taskViewRepository;
        this.projectService = projectService;
        this.sectionService = sectionService;
        this.userService = userService;
        this.accessPolicy = accessPolicy;
        this.sortOrderService = sortOrderService;
        this.subtaskTreeService = subtaskTreeService;
        this.usageLimits = usageLimits;
        this.events = events;
        this.clock = clock;
    }

    // ---- API ----

    @Transactional(readOnly = true)
    public TaskResponse get(UUID userId, UUID taskId) {
        requireReadable(userId, taskId);
        return toResponse(userId, taskId);
    }

    /**
     * Tạo task theo BẢNG QUYẾT ĐỊNH spec 12.3 — kiểm THEO THỨ TỰ, dòng đầu khớp thì dừng:
     * 1 `id` đã có (12.11) → 2 not found 404 → 3 bad request 400 → 4 `project-archived` 409 (cha ở dự án lưu trữ) →
     * 5 `section-archived` 409 (cha ở section lưu trữ) → 6 `parent-completed` 409 → 7 / 8 `target-archived` 422 (đích
     * không có cha) → 9 `limit-reached` 422 (sau khi khoá dự án). Kiểm tra đầu vào thuần (due / duration — cùng loại
     * Bean Validation) chạy ngay sau dòng 1. Trả Created: fresh (201) hoặc replay (200 + Idempotent-Replay).
     */
    public Created<TaskResponse> create(UUID userId, CreateTaskRequest request) {
        // Dòng 1: `id` do client sinh — kiểm ĐẦU TIÊN, trước mọi 404 / 400 / 409 / 422
        if (request.id() != null) {
            Optional<Task> existing = taskRepository.findById(request.id());
            if (existing.isPresent()) {
                return replayOrConflict(userId, existing.get(), request);
            }
        }
        LocalDateTime dueDatetime = TaskDueRules.normalize(request.dueDatetime());
        TaskDueRules.checkSingleDue(request.dueDate(), dueDatetime);
        TaskDueRules.checkDuration(dueDatetime, request.durationMinutes());

        // Dòng 2: 404 — parent / project / section không tồn tại hoặc không thuộc quyền
        Task parent = request.parentId() == null ? null : requireReadable(userId, request.parentId());
        Project project;
        if (request.projectId() != null) {
            project = projectService.requireReadable(userId, request.projectId());
        } else if (parent != null) {
            project = projectService.requireReadable(userId, parent.getProjectId(), ProblemTexts.TASK_NOT_FOUND);
        } else {
            project = projectService.inboxOf(userId); // quyết định Q9: vắng projectId, không cha → Inbox
        }

        UUID sectionId;
        if (parent != null) {
            // Dòng 3: 400 — con luôn cùng dự án + section với cha; vượt 4 cấp
            if (!parent.getProjectId().equals(project.getId())) {
                throw ApiException.badRequest(ProblemTexts.PARENT_NOT_IN_PROJECT);
            }
            if (request.sectionId() != null && !request.sectionId().equals(parent.getSectionId())) {
                throw ApiException.badRequest(ProblemTexts.PARENT_NOT_IN_SECTION);
            }
            sectionId = parent.getSectionId();
            if (subtaskTreeService.depth(tree(project.getId()), parent.getId()) + 1 > SubtaskTreeService.MAX_DEPTH) {
                throw ApiException.badRequest(ProblemTexts.SUBTASK_TOO_DEEP);
            }
            // Dòng 4, 5, 6: 409 — CHA ở dự án lưu trữ → CHA ở section lưu trữ → cha đã hoàn thành (đúng thứ tự này)
            projectService.requireWritable(userId, parent.getProjectId(), ProblemTexts.TASK_NOT_FOUND);
            requireSectionNotArchived(parent);
            if (parent.getCompletedAt() != null) {
                throw ApiException.conflict(ProblemTexts.PARENT_COMPLETED).withCode(ProblemCodes.PARENT_COMPLETED);
            }
        } else {
            sectionId = request.sectionId();
            if (sectionId != null) {
                // 404 nếu không tồn tại, 400 nếu thuộc dự án khác (dòng 3), 422 nếu section đích lưu trữ (dòng 8)
                sectionService.requireTargetSection(userId, project.getId(), sectionId);
            }
            projectService.requireTarget(userId, project.getId()); // dòng 7: dự án đích lưu trữ → 422
        }
        // Dòng 9: 422 — khoá dự án (UsageLimits.lockProject, bên trong) rồi mới đếm 300 task đang mở
        checkActiveTaskLimit(project.getId(), 1);
        // ĐỌC LẠI sau khoá (mọi thứ đã nạp / kiểm trước khoá có thể cũ: archive / move / close / xoá vừa commit trong lúc
        // chờ P — các thao tác đó cũng khoá P): dự án → cha → section, đúng thứ tự dòng 4 / 5 / 6 của bảng quyết định.
        projectService.recheckAfterLock(project, parent == null); // 409 project-archived (cha) / 422 target-archived
        if (parent != null) {
            refresh(parent); // 404 nếu cha vừa bị xoá
            if (!parent.getProjectId().equals(project.getId())) {
                // cha vừa chuyển sang dự án khác: chèn con với project_id cũ sẽ làm cây lệch dự án
                throw ApiException.conflict(ProblemTexts.VERSION_CONFLICT).withCode(ProblemCodes.VERSION_CONFLICT);
            }
            sectionId = parent.getSectionId(); // cha vừa đổi section (move trong dự án) → con theo section mới
            if (sectionId != null) {
                sectionService.recheckAfterLock(sectionId, true); // 409 section-archived
            }
            if (parent.getCompletedAt() != null) {
                throw ApiException.conflict(ProblemTexts.PARENT_COMPLETED).withCode(ProblemCodes.PARENT_COMPLETED);
            }
        } else if (sectionId != null) {
            sectionService.recheckAfterLock(sectionId, false); // 404 (vừa xoá) / 422 target-archived
        }

        UUID workspaceId = project.getWorkspace().getId(); // phi chuẩn: workspace của dự án (spec 10)
        long sortOrder = placeForInsert(container(project.getId(), sectionId, request.parentId()), request.afterId());
        Task task = new Task(workspaceId, project.getId(), sectionId, request.parentId(), userId,
                request.content(), request.description() == null ? "" : request.description(),
                request.priority() == null ? Task.DEFAULT_PRIORITY : request.priority(),
                request.dueDate(), dueDatetime, request.durationMinutes(), request.deadlineDate(), sortOrder);
        task.assignId(request.id()); // null → BaseEntity tự sinh ở @PrePersist
        taskRepository.save(task);
        return Created.fresh(toResponse(userId, task.getId()));
    }

    public TaskResponse update(UUID userId, UUID taskId, UpdateTaskRequest request) {
        Task task = requireWritable(userId, taskId); // 404 / 409 theo thứ tự cũ
        task = lockAndRecheck(task); // khoá P rồi đọc lại: dự án / section vừa lưu trữ, task vừa xoá / chuyển / đổi version
        if (request.version() != task.getVersion()) {
            throw ApiException.conflict(ProblemTexts.VERSION_CONFLICT)
                    .withCode(ProblemCodes.VERSION_CONFLICT)
                    .withProperty("current", toResponse(userId, taskId));
        }
        if (request.content() != null) {
            task.setContent(request.content());
        }
        if (request.description() != null) {
            task.setDescription(request.description());
        }
        if (request.priority() != null) {
            task.setPriority(request.priority());
        }
        LocalDate dueDate = task.getDueDate();
        LocalDateTime dueDatetime = task.getDueDatetime();
        if (request.touchesDue()) {
            dueDate = request.dueDate() == null ? null : request.dueDate().orElse(null);
            dueDatetime = TaskDueRules.normalize(request.dueDatetime() == null ? null : request.dueDatetime().orElse(null));
        }
        Integer duration;
        if (request.durationMinutes() != null) {
            duration = request.durationMinutes().orElse(null);
        } else {
            duration = dueDatetime == null ? null : task.getDurationMinutes(); // mất giờ → tự xoá duration (spec 5.2)
        }
        applyDue(task, dueDate, dueDatetime, duration);
        if (request.deadlineDate() != null) {
            task.setDeadlineDate(request.deadlineDate().orElse(null));
        }
        // Hibernate chỉ UPDATE (tăng version, đổi updated_at) khi giá trị thực sự khác — PATCH không đổi gì giữ nguyên.
        return toResponse(userId, taskId);
    }

    /**
     * Xoá VĨNH VIỄN task + cả cây con (DB ON DELETE CASCADE) — spec 5.7. Dự án lưu trữ → 409 project-archived, section
     * lưu trữ → 409 section-archived (bản 3.1 BỎ ngoại lệ "xoá được trong dự án lưu trữ" — I15): dùng requireWritable.
     * Xoá lại → 404 (app coi là đã xoá xong).
     */
    public void delete(UUID userId, UUID taskId) {
        Task task = requireWritable(userId, taskId);
        task = lockAndRecheck(task); // khoá P rồi đọc lại (cùng cổng reopen / close / reorder): không xoá trong dự án vừa lưu trữ
        taskRepository.delete(task);
        taskRepository.flush();
    }

    /**
     * Lưu trữ section → mọi task đang mở của nó (kèm cây con: sub-task luôn cùng section với task gốc) thành đã
     * xong cùng completed_at = archivedAt VÀ cùng một completion_batch_id mới (listener tự sinh, spec 5.1 / 10.2).
     * Đổi qua entity nên mỗi task chuyển trạng thái tăng version (spec 5.6). Cùng transaction với SectionService.
     */
    @EventListener
    public void onSectionArchived(SectionArchivedEvent event) {
        UUID batchId = UUID.randomUUID();
        taskRepository.findBySectionIdAndCompletedAtIsNull(event.sectionId())
                .forEach(task -> task.complete(event.archivedAt(), batchId));
    }

    // ---- Dùng chung cho các service task khác ----

    /** Task trong dự án user đọc được, không thì 404 "Không tìm thấy công việc". */
    public Task requireReadable(UUID userId, UUID taskId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> ApiException.notFound(ProblemTexts.TASK_NOT_FOUND));
        projectService.requireReadable(userId, task.getProjectId(), ProblemTexts.TASK_NOT_FOUND);
        return task;
    }

    /** Như requireReadable + dự án lưu trữ → 409 project-archived; section lưu trữ → 409 section-archived. */
    public Task requireWritable(UUID userId, UUID taskId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> ApiException.notFound(ProblemTexts.TASK_NOT_FOUND));
        projectService.requireWritable(userId, task.getProjectId(), ProblemTexts.TASK_NOT_FOUND);
        requireSectionNotArchived(task);
        return task;
    }

    /**
     * Khoá dự án chứa task (P) rồi ĐỌC LẠI sau khoá — cổng chung của reopen / undo, duplicate, reorder task: task có thể
     * vừa bị xoá (404) / chuyển sang dự án khác (409 version-conflict), dự án vừa lưu trữ (409 project-archived), section
     * vừa lưu trữ (409 section-archived). Trả entity đã đọc lại (managed). Flush trước refresh để không mất thay đổi chưa
     * ghi của cùng transaction (tasks/batch gọi nhiều lần).
     */
    public Task lockAndRecheck(Task task) {
        UUID projectId = task.getProjectId();
        usageLimits.lockProject(projectId);
        entityManager.flush();
        Task fresh = taskRepository.findById(task.getId())
                .orElseThrow(() -> ApiException.notFound(ProblemTexts.TASK_NOT_FOUND));
        refresh(fresh);
        if (!fresh.getProjectId().equals(projectId)) {
            throw ApiException.conflict(ProblemTexts.VERSION_CONFLICT).withCode(ProblemCodes.VERSION_CONFLICT);
        }
        projectService.recheckWritableAfterLock(projectId);
        if (fresh.getSectionId() != null && sectionService.isArchivedNow(fresh.getSectionId())) {
            throw SectionService.sectionArchived();
        }
        return fresh;
    }

    /** Đọc thẳng DB (sau khi đã khoá dự án): section có đang lưu trữ không. */
    public boolean isSectionArchivedNow(UUID sectionId) {
        return sectionService.isArchivedNow(sectionId);
    }

    /** Nạp lại entity sau khi một native UPDATE đã xoá persistence context (không kiểm quyền — caller đã kiểm). */
    public Task reload(UUID taskId) {
        return taskRepository.findById(taskId).orElseThrow(() -> ApiException.notFound(ProblemTexts.TASK_NOT_FOUND));
    }

    /** Đọc lại hàng từ DB (sau khi đã khoá dự án) → entity phản ánh bản đã commit mới nhất. Hàng đã bị xoá → 404. */
    public Task refresh(Task task) {
        try {
            entityManager.refresh(task);
        } catch (jakarta.persistence.EntityNotFoundException e) {
            throw ApiException.notFound(ProblemTexts.TASK_NOT_FOUND);
        }
        return task;
    }

    /** Vùng chứa: con của parentId; hoặc task gốc của (dự án, section / không section). Sắp theo (sortOrder, id). */
    public List<Task> container(UUID projectId, UUID sectionId, UUID parentId) {
        return taskRepository.findContainer(projectId, sectionId, parentId);
    }

    public List<SubtaskTreeService.Node> treeNodes(UUID projectId) {
        return taskRepository.findTreeRows(projectId).stream()
                .map(row -> new SubtaskTreeService.Node((UUID) row[0], (UUID) row[1], (Long) row[2], (Instant) row[3],
                        (UUID) row[4]))
                .toList();
    }

    public SubtaskTreeService.Tree tree(UUID projectId) {
        return subtaskTreeService.tree(treeNodes(projectId));
    }

    public List<Task> loadAll(Collection<UUID> ids) {
        return ids.isEmpty() ? List.of() : taskRepository.findAllById(ids);
    }

    public Task save(Task task) {
        return taskRepository.save(task);
    }

    /**
     * Giới hạn 300 task ĐANG MỞ / dự án (gồm sub-task) — mọi thao tác làm tăng số đếm gọi trước khi ghi (spec 2.1).
     * Khoá dự án (SELECT … FOR NO KEY UPDATE, UsageLimits.lockProject) TRƯỚC khi đếm để hai request song song không cùng
     * lọt qua (spec 8.2 #9). Thao tác chạm nhiều dự án đích (tasks/batch) tự gọi lockProjects(đích, id tăng dần)
     * trước; gọi lại ở đây là no-op.
     */
    public void checkActiveTaskLimit(UUID projectId, long adding) {
        if (adding <= 0) {
            return;
        }
        usageLimits.lockProject(projectId);
        usageLimits.check(LimitKind.PROJECT_ACTIVE_TASKS, taskRepository.countOpenInProject(projectId), adding);
    }

    /**
     * Ghi due + duration đã kiểm (một trong hai due; duration cần giờ). Task MẤT giờ → phát TaskTimeClearedEvent
     * (M4 xoá nhắc tương đối). Dùng cho PATCH, tasks/batch SCHEDULE, Reschedule quá hạn.
     */
    public void applyDue(Task task, LocalDate dueDate, LocalDateTime dueDatetime, Integer durationMinutes) {
        TaskDueRules.checkSingleDue(dueDate, dueDatetime);
        TaskDueRules.checkDuration(dueDatetime, durationMinutes);
        boolean hadTime = task.getDueDatetime() != null;
        task.setDue(dueDate, dueDatetime);
        task.setDurationMinutes(durationMinutes);
        if (hadTime && dueDatetime == null) {
            events.publishEvent(new TaskTimeClearedEvent(List.of(task.getId())));
        }
    }

    /**
     * Như findOverdue nhưng KHOÁ P của mọi dự án có task quá hạn (id tăng dần) rồi truy vấn LẠI trên persistence context
     * sạch: dự án vừa lưu trữ / task vừa xong / vừa dời ngày trong lúc chờ không bị ghi đè (Reschedule, spec 12.5).
     */
    public List<Task> findOverdueLocked(UUID userId) {
        List<Task> first = findOverdue(userId);
        if (first.isEmpty()) {
            return first;
        }
        usageLimits.lockProjects(first.stream().map(Task::getProjectId).collect(java.util.stream.Collectors.toSet()));
        entityManager.flush();
        entityManager.clear(); // truy vấn bên dưới phải đọc hàng đã commit mới nhất, không trả entity cũ trong cache
        return findOverdue(userId);
    }

    /** Mọi task trong khối Quá hạn (due_day < hôm nay, chưa xong, không tính dự án lưu trữ) của user — Reschedule (spec 12.5). */
    public List<Task> findOverdue(UUID userId) {
        LocalDate today = TaskDueRules.today(clock.instant(), userService.timeZoneOf(userId));
        List<UUID> workspaceIds = accessPolicy.readableWorkspaceIds(userId);
        return workspaceIds.isEmpty() ? List.of()
                : taskRepository.findOverdue(today, today.atStartOfDay(), workspaceIds);
    }

    /**
     * Tạo / chuyển / sao chép: afterId == null (VẮNG) → CUỐI vùng chứa; Optional.empty() ("afterId": null) → ĐẦU;
     * có giá trị → ngay sau task đó (khác vùng chứa → 400). siblings không gồm task đang đặt. Hết khoảng trống →
     * TỰ ghi đánh số lại cả vùng chứa bằng native UPDATE (writeSortOrders) — caller KHÔNG phải ghi lại.
     */
    public long placeForInsert(List<Task> siblings, Optional<UUID> afterId) {
        SortOrderService.Placement placement = sortOrderService.placeAt(items(siblings), afterId);
        writeRenumber(placement);
        return placement.sortOrder();
    }

    /**
     * Reorder (ReorderService, Part 3 — đã kiểm "afterId bắt buộc có mặt"): afterId null → ĐẦU vùng chứa (spec 12.4).
     * Tự ghi đánh số lại anh em; caller gọi writeSortOrder(taskId, kết quả) cho CHÍNH task được đặt.
     */
    public long placeForReorder(UUID movedTaskId, List<Task> siblings, UUID afterId) {
        SortOrderService.Placement placement = sortOrderService.placeAfter(items(siblings), afterId);
        if (placement.renumbers()) {
            // khoá hàng id TĂNG DẦN (anh em + task được đặt, sắp ghi ngay sau) trước mọi native UPDATE
            List<UUID> toLock = new java.util.ArrayList<>(placement.renumbered().keySet());
            toLock.add(movedTaskId);
            usageLimits.lockTasks(toLock);
        }
        writeRenumber(placement);
        return placement.sortOrder();
    }

    /** Native UPDATE sort_order: KHÔNG tăng version, ghi updated_at (spec 5.6). Xoá persistence context. */
    public void writeSortOrder(UUID taskId, long sortOrder) {
        taskRepository.updateSortOrder(taskId, sortOrder, clock.instant());
    }

    public void writeSortOrders(Map<UUID, Long> sortOrders) {
        sortOrders.forEach(this::writeSortOrder);
    }

    /**
     * Cây con đi theo task được chuyển (move): đổi project_id / section_id / workspace_id bằng native UPDATE — KHÔNG
     * tăng version, ghi updated_at (spec 5.6). ids rỗng → bỏ qua. Xoá persistence context.
     */
    public void relocate(Collection<UUID> ids, UUID projectId, UUID sectionId, UUID workspaceId) {
        if (!ids.isEmpty()) {
            taskRepository.relocate(ids, projectId, sectionId, workspaceId, clock.instant());
        }
    }

    /** Flush rồi đọc lại bằng SQL → response luôn phản ánh version/updated_at đã ghi. */
    public TaskResponse toResponse(UUID userId, UUID taskId) {
        taskRepository.flush();
        TaskView view = taskViewRepository.findOne(accessPolicy.readableWorkspaceIds(userId), taskId)
                .orElseThrow(() -> ApiException.notFound(ProblemTexts.TASK_NOT_FOUND));
        return TaskMapper.toResponse(view, TaskDueRules.localNow(clock.instant(), userService.timeZoneOf(userId)));
    }

    // ---- nội bộ ----

    /**
     * `id` client đã tồn tại (spec 12.11): của CÙNG người dùng và các field nội dung của yêu cầu tạo KHỚP bản ghi hiện
     * có → phát lại (KHÔNG ghi, KHÔNG khoá, KHÔNG đếm lại giới hạn); khác nội dung hoặc của người khác → 409
     * id-conflict (cùng một câu trả lời, không lộ gì thêm).
     */
    private Created<TaskResponse> replayOrConflict(UUID userId, Task existing, CreateTaskRequest request) {
        if (!accessPolicy.canRead(userId, existing.getWorkspaceId()) || !sameContent(userId, existing, request)) {
            throw ApiException.conflict(ProblemTexts.ID_CONFLICT).withCode(ProblemCodes.ID_CONFLICT);
        }
        return Created.replay(toResponse(userId, existing.getId()));
    }

    /**
     * So các field nội dung (bỏ qua afterId). projectId / sectionId vắng được hiểu như lúc tạo: có cha → suy ra từ
     * cha; không cha → vắng projectId = Inbox, vắng sectionId = không section.
     */
    private boolean sameContent(UUID userId, Task task, CreateTaskRequest request) {
        boolean sameProject = request.projectId() != null
                ? request.projectId().equals(task.getProjectId())
                : request.parentId() != null || task.getProjectId().equals(projectService.inboxOf(userId).getId());
        boolean sameSection = request.parentId() != null || Objects.equals(request.sectionId(), task.getSectionId());
        return sameProject && sameSection
                && Objects.equals(request.parentId(), task.getParentId())
                && request.content().equals(task.getContent())
                && (request.description() == null ? "" : request.description()).equals(task.getDescription())
                && (request.priority() == null ? Task.DEFAULT_PRIORITY : request.priority()) == task.getPriority()
                && Objects.equals(request.dueDate(), task.getDueDate())
                && Objects.equals(TaskDueRules.normalize(request.dueDatetime()), task.getDueDatetime())
                && Objects.equals(request.durationMinutes(), task.getDurationMinutes())
                && Objects.equals(request.deadlineDate(), task.getDeadlineDate());
    }

    private void requireSectionNotArchived(Task task) {
        if (task.getSectionId() != null && sectionService.isArchived(task.getSectionId())) {
            throw SectionService.sectionArchived();
        }
    }

    private static List<SortOrderService.Item> items(List<Task> tasks) {
        return tasks.stream().map(t -> new SortOrderService.Item(t.getId(), t.getSortOrder())).toList();
    }

    private void writeRenumber(SortOrderService.Placement placement) {
        if (placement.renumbers()) {
            writeSortOrders(placement.renumbered());
        }
    }
}
