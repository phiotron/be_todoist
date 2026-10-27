package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ProblemTexts;
import com.taskly.dto.reorder.ReorderRequest;
import com.taskly.dto.reorder.ReorderResponse;
import com.taskly.entity.Project;
import com.taskly.entity.Section;
import com.taskly.entity.Task;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sắp xếp thủ công trong vùng chứa hiện tại (đổi vùng chứa dùng move). Vùng chứa (spec 10.1): dự án thường cùng
 * (workspace, dự án cha); section CHƯA lưu trữ của dự án; task gốc của (dự án, section); subtask của cha.
 * Quyền qua các service tính năng: dự án lưu trữ → 409 project-archived; section lưu trữ → 409 section-archived.
 * Task dùng native UPDATE (không tăng version, spec 5.6).
 */
@Service
@Transactional
public class ReorderService {

    private final ProjectService projectService;
    private final SectionService sectionService;
    private final TaskService taskService;
    private final SortOrderService sortOrderService;
    private final LabelService labelService;
    private final FilterService filterService;

    public ReorderService(ProjectService projectService, SectionService sectionService, TaskService taskService,
                          SortOrderService sortOrderService, LabelService labelService, FilterService filterService) {
        this.projectService = projectService;
        this.sectionService = sectionService;
        this.taskService = taskService;
        this.sortOrderService = sortOrderService;
        this.labelService = labelService;
        this.filterService = filterService;
    }

    public ReorderResponse reorder(UUID userId, ReorderRequest request) {
        UUID afterId = request.afterId().orElse(null); // "afterId": null (Optional.empty) = lên đầu vùng chứa
        long sortOrder = switch (request.type()) {
            case PROJECT -> reorderProject(userId, request.id(), afterId);
            case SECTION -> reorderSection(userId, request.id(), afterId);
            case TASK -> reorderTask(userId, request.id(), afterId);
            case LABEL -> labelService.reorder(userId, request.id(), afterId);
            case FILTER -> filterService.reorder(userId, request.id(), afterId);
        };
        return new ReorderResponse(request.type(), request.id(), sortOrder);
    }

    private long reorderProject(UUID userId, UUID projectId, UUID afterId) {
        Project project = projectService.requireWritable(userId, projectId);
        if (project.isInbox()) {
            throw ApiException.forbidden(ProblemTexts.INBOX_PROTECTED); // mọi thao tác nhắm vào chính Inbox → 403 (spec m2)
        }
        List<Project> siblings = projectService.container(project.getWorkspace().getId(), project.getParentId())
                .stream().filter(p -> !p.getId().equals(projectId)).toList();
        SortOrderService.Placement placement = sortOrderService.placeAfter(
                siblings.stream().map(p -> new SortOrderService.Item(p.getId(), p.getSortOrder())).toList(), afterId);
        if (placement.renumbers()) {
            siblings.forEach(p -> p.setSortOrder(placement.renumbered().get(p.getId())));
        }
        project.setSortOrder(placement.sortOrder());
        return placement.sortOrder();
    }

    private long reorderSection(UUID userId, UUID sectionId, UUID afterId) {
        Section section = sectionService.requireWritable(userId, sectionId);
        List<Section> siblings = sectionService.container(section.getProjectId()).stream()
                .filter(s -> !s.getId().equals(sectionId)).toList();
        SortOrderService.Placement placement = sortOrderService.placeAfter(
                siblings.stream().map(s -> new SortOrderService.Item(s.getId(), s.getSortOrder())).toList(), afterId);
        if (placement.renumbers()) {
            siblings.forEach(s -> s.setSortOrder(placement.renumbered().get(s.getId())));
        }
        section.setSortOrder(placement.sortOrder());
        return placement.sortOrder();
    }

    private long reorderTask(UUID userId, UUID taskId, UUID afterId) {
        Task task = taskService.requireWritable(userId, taskId);
        List<Task> siblings = taskService.container(task.getProjectId(), task.getSectionId(), task.getParentId())
                .stream().filter(t -> !t.getId().equals(taskId)).toList();
        long sortOrder = taskService.placeForReorder(siblings, afterId); // tự ghi đánh số lại anh em (native UPDATE)
        taskService.writeSortOrder(taskId, sortOrder); // native UPDATE: KHÔNG tăng version, vẫn ghi updated_at (spec 5.6)
        return sortOrder;
    }
}
