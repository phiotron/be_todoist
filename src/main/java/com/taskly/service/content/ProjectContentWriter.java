package com.taskly.service.content;

import com.taskly.entity.Project;
import com.taskly.entity.Section;
import com.taskly.entity.Task;
import com.taskly.repository.SectionRepository;
import com.taskly.service.SectionService;
import com.taskly.service.SortOrderService;
import com.taskly.service.TaskLabelService;
import com.taskly.service.TaskService;
import com.taskly.service.content.ProjectContent.NewSection;
import com.taskly.service.content.ProjectContent.NewTask;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Ghi nội dung (M11) vào một dự án: section mới thêm vào CUỐI section đang dùng, task gốc không section thêm vào cuối
 * vùng "không section", con cháu giữ cấu trúc (sort_order 1024, 2048… trong mỗi vùng chứa mới — luôn ở cuối nên không
 * phải đánh số lại ai). Task mới luôn đang mở, version 0, creator = người thao tác; task có source (nhân bản) chép đủ
 * trường qua Task.copyOf + label của bản gốc. Caller đã khoá dự án đích và kiểm giới hạn TRƯỚC khi gọi.
 */
@Component
public class ProjectContentWriter {

    private final TaskService taskService;
    private final SectionService sectionService;
    private final SectionRepository sectionRepository;
    private final TaskLabelService taskLabelService;

    public ProjectContentWriter(TaskService taskService, SectionService sectionService,
                                SectionRepository sectionRepository, TaskLabelService taskLabelService) {
        this.taskService = taskService;
        this.sectionService = sectionService;
        this.sectionRepository = sectionRepository;
        this.taskLabelService = taskLabelService;
    }

    /** Thêm toàn bộ nội dung vào cuối dự án; trả các section vừa tạo (theo thứ tự). */
    public List<Section> append(Project target, ProjectContent content, UUID creatorId) {
        Map<UUID, UUID> copies = new HashMap<>();
        long taskBase = lastSortOrder(taskService.container(target.getId(), null, null).stream()
                .map(Task::getSortOrder).toList());
        writeTasks(target, null, null, content.tasks(), taskBase, creatorId, copies);
        long sectionBase = lastSortOrder(sectionService.container(target.getId()).stream()
                .map(Section::getSortOrder).toList());
        List<Section> created = new ArrayList<>();
        long order = sectionBase;
        for (NewSection newSection : content.sections()) {
            order += SortOrderService.STEP;
            Section section = sectionRepository.save(
                    new Section(target.getId(), newSection.name(), newSection.description(), order));
            created.add(section);
            writeTasks(target, section.getId(), null, newSection.tasks(), 0, creatorId, copies);
        }
        finish(copies);
        return created;
    }

    /** Task gốc (kèm cây con) vào một section MỚI tạo (trống) của dự án đích. */
    public void writeIntoNewSection(Project target, Section section, List<NewTask> tasks, UUID creatorId) {
        Map<UUID, UUID> copies = new HashMap<>();
        writeTasks(target, section.getId(), null, tasks, 0, creatorId, copies);
        finish(copies);
    }

    private void writeTasks(Project target, UUID sectionId, UUID parentId, List<NewTask> tasks, long base,
                            UUID creatorId, Map<UUID, UUID> copies) {
        long order = base;
        for (NewTask node : tasks) {
            order += SortOrderService.STEP;
            Task copy;
            if (node.source() != null) {
                copy = Task.copyOf(node.source(), parentId, creatorId, order);
                copy.placeIn(target.getId(), sectionId, parentId);
            } else {
                copy = new Task(target.getWorkspace().getId(), target.getId(), sectionId, parentId, creatorId,
                        node.content(), node.description(), node.priority(), null, null, null, null, order);
            }
            taskService.save(copy);
            if (node.source() != null) {
                copies.put(node.source().getId(), copy.getId());
            }
            writeTasks(target, sectionId, copy.getId(), node.children(), 0, creatorId, copies);
        }
    }

    /** Ghi xuống DB rồi chép label (task_labels cần hàng task đã có). */
    private void finish(Map<UUID, UUID> copies) {
        taskService.flush();
        taskLabelService.copyLabels(copies);
    }

    private static long lastSortOrder(List<Long> sortOrders) {
        return sortOrders.stream().mapToLong(Long::longValue).max().orElse(0);
    }
}
