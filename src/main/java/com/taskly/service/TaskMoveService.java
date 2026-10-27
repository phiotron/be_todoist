package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ProblemCodes;
import com.taskly.common.error.ErrorMessage;
import com.taskly.dto.task.MoveTaskRequest;
import com.taskly.dto.task.TaskResponse;
import com.taskly.entity.Project;
import com.taskly.entity.Task;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * move (BR-07, BR-08, spec 5.6, 10.1, 12.3): kiểm NGUỒN trước (task thuộc dự án / section lưu trữ → 409; task đã
 * hoàn thành → 409 task-completed), rồi ĐÍCH (thấy được 404; dự án / section đích lưu trữ → 422 target-archived);
 * cùng workspace (400); section thuộc dự án đích (400); cha cùng dự án đích (400), chưa xong (409 parent-completed),
 * cha ở section lưu trữ → 409 section-archived (spec 3.1); kiểm độ sâu + vòng lặp; sang dự án khác thì khoá dự án
 * đích rồi mới đếm giới hạn 300 task đang mở (đếm cả task đang mở của cây con).
 *
 * <p>Ghi (spec 5.6): CHỈ task ĐƯỢC chuyển đổi bằng entity (placeIn → version + 1). Cây con đi theo bằng native
 * TaskService.relocate (đổi project_id / section_id / workspace_id, ghi updated_at, KHÔNG tăng version). sort_order
 * qua TaskService.writeSortOrder (native). Mỗi native UPDATE xoá persistence context nên thứ tự bắt buộc là: native
 * của người khác (đánh số lại) → relocate cây con → nạp lại task → placeIn → writeSortOrder (flush placeIn trước).
 * Chỉ khoá MỘT dự án (đích) nên không thể deadlock với move ngược chiều.
 */
@Service
@Transactional
public class TaskMoveService {

    private final TaskService taskService;
    private final ProjectService projectService;
    private final SectionService sectionService;
    private final SubtaskTreeService subtaskTreeService;

    public TaskMoveService(TaskService taskService, ProjectService projectService, SectionService sectionService,
                           SubtaskTreeService subtaskTreeService) {
        this.taskService = taskService;
        this.projectService = projectService;
        this.sectionService = sectionService;
        this.subtaskTreeService = subtaskTreeService;
    }

    public TaskResponse move(UUID userId, UUID taskId, MoveTaskRequest request) {
        Task task = taskService.requireWritable(userId, taskId); // 404; NGUỒN lưu trữ → 409 (kiểm trước đích)
        if (task.getCompletedAt() != null) {
            throw ApiException.conflict(ErrorMessage.TASK_COMPLETED).withCode(ProblemCodes.TASK_COMPLETED);
        }
        Project source = projectService.requireReadable(userId, task.getProjectId(), ErrorMessage.TASK_NOT_FOUND);
        Project target = projectService.requireTarget(userId, request.projectId()); // 404; ĐÍCH lưu trữ → 422
        UUID workspaceId = target.getWorkspace().getId();
        if (!workspaceId.equals(source.getWorkspace().getId())) {
            throw ApiException.badRequest(ErrorMessage.CROSS_WORKSPACE_MOVE);
        }

        List<SubtaskTreeService.Node> nodes = new ArrayList<>(taskService.treeNodes(source.getId()));
        if (!target.getId().equals(source.getId())) {
            nodes.addAll(taskService.treeNodes(target.getId()));
        }
        SubtaskTreeService.Tree tree = subtaskTreeService.tree(nodes);

        UUID sectionId = request.sectionId();
        if (request.parentId() != null) {
            Task parent = taskService.requireReadable(userId, request.parentId());
            if (!parent.getProjectId().equals(target.getId())) {
                throw ApiException.badRequest(ErrorMessage.PARENT_NOT_IN_PROJECT);
            }
            if (parent.getSectionId() != null && sectionService.isArchived(parent.getSectionId())) {
                throw SectionService.sectionArchived(); // cha ở section lưu trữ → 409 (spec 3.1), không phải 422
            }
            subtaskTreeService.checkCanAttach(tree, taskId, parent.getId());
            sectionId = parent.getSectionId();
        } else if (sectionId != null) {
            sectionService.requireTargetSection(userId, target.getId(), sectionId);
        }

        List<UUID> descendants = subtaskTreeService.descendants(tree, taskId);
        if (!target.getId().equals(source.getId())) {
            List<UUID> moving = new ArrayList<>(descendants);
            moving.add(taskId);
            long openMoving = taskService.loadAll(moving).stream().filter(t -> t.getCompletedAt() == null).count();
            taskService.checkActiveTaskLimit(target.getId(), openMoving); // lockProject(đích) rồi mới đếm
        }
        List<Task> siblings = taskService.container(target.getId(), sectionId, request.parentId()).stream()
                .filter(t -> !t.getId().equals(taskId))
                .toList();
        long sortOrder = taskService.placeForInsert(siblings, request.afterId()); // vắng = cuối, null = đầu

        taskService.relocate(descendants, target.getId(), sectionId, workspaceId); // cây con: native, không version
        Task moved = taskService.reload(taskId); // native ở trên đã xoá persistence context
        moved.placeIn(target.getId(), sectionId, request.parentId()); // task được chuyển: version + 1 (nếu đổi thật)
        taskService.writeSortOrder(taskId, sortOrder); // flush placeIn rồi UPDATE sort_order (native)
        return taskService.toResponse(userId, taskId);
    }
}
