package com.taskly.service;

import com.taskly.common.error.ErrorMessage;
import com.taskly.common.error.ApiException;
import com.taskly.common.error.ProblemCodes;
import com.taskly.dto.task.TaskResponse;
import com.taskly.entity.ActivityEventType;
import com.taskly.entity.Task;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sao chép task (spec 5.4, 10.1): bản sao gốc ngay sau bản gốc, chưa hoàn thành; chỉ chép con cháu ĐANG MỞ (giữ
 * cấu trúc, con đã xong và cả nhánh dưới nó không chép). Mọi bản sao đều đang mở → khoá dự án
 * (UsageLimits.lockProject, bên trong checkActiveTaskLimit) rồi mới đếm giới hạn 300 (spec 8.2 #9). Bản sao có
 * workspace_id, version 0, completion_batch_id null. Đánh số lại anh em (khi hết khoảng trống) là native UPDATE
 * qua TaskService.writeSortOrders — KHÔNG tăng version của anh em.
 */
@Service
@Transactional
public class TaskDuplicateService {

    private final TaskService taskService;
    private final SubtaskTreeService subtaskTreeService;
    private final TaskLabelService taskLabelService;
    private final ActivityService activityService; // M10

    public TaskDuplicateService(TaskService taskService, SubtaskTreeService subtaskTreeService,
                                TaskLabelService taskLabelService, ActivityService activityService) {
        this.activityService = activityService;
        this.taskService = taskService;
        this.subtaskTreeService = subtaskTreeService;
        this.taskLabelService = taskLabelService;
    }

    public TaskResponse duplicate(UUID userId, UUID taskId) {
        Task original = taskService.requireWritable(userId, taskId);
        original = taskService.lockAndRecheck(original); // khoá P + đọc lại (dự án / section lưu trữ, task chuyển / xoá)
        if (original.getParentId() != null && taskService.refresh(taskService.reload(original.getParentId())).getCompletedAt() != null) {
            // bản sao luôn đang mở: không được đặt dưới cha đã hoàn thành (spec 5.4, 12.3 — như create / move)
            throw ApiException.conflict(ErrorMessage.PARENT_COMPLETED).withCode(ProblemCodes.PARENT_COMPLETED);
        }
        List<SubtaskTreeService.Node> toCopy =
                subtaskTreeService.subtreeToCopy(taskService.tree(original.getProjectId()), taskId);
        taskService.checkActiveTaskLimit(original.getProjectId(), toCopy.size()); // khoá dự án, rồi đếm
        Map<UUID, Task> originals = taskService.loadAll(toCopy.stream().map(SubtaskTreeService.Node::id).toList())
                .stream().collect(Collectors.toMap(Task::getId, Function.identity()));

        List<Task> siblings = taskService.container(original.getProjectId(), original.getSectionId(),
                original.getParentId());
        // Ngay sau bản gốc. Hết khoảng trống → TaskService đánh số lại anh em bằng native UPDATE (xoá persistence
        // context): từ đây `originals` chỉ còn dùng để ĐỌC giá trị (getter), không sửa.
        long rootSortOrder = taskService.placeForInsert(siblings, Optional.of(taskId));

        Map<UUID, UUID> copyIdByOriginal = new HashMap<>();
        UUID rootCopyId = null;
        for (SubtaskTreeService.Node node : toCopy) { // cha luôn đứng trước con
            Task source = originals.get(node.id());
            boolean root = node.id().equals(taskId);
            Task copy = Task.copyOf(source,
                    root ? source.getParentId() : copyIdByOriginal.get(source.getParentId()),
                    userId,
                    root ? rootSortOrder : source.getSortOrder());
            UUID copyId = taskService.save(copy).getId();
            copyIdByOriginal.put(source.getId(), copyId);
            if (root) {
                rootCopyId = copyId;
            }
        }
        taskService.flush();
        taskLabelService.copyLabels(copyIdByOriginal); // chép label của bản gốc + mọi con được chép (spec 10.1)
        activityService.tasks(userId, copyIdByOriginal.values(), ActivityEventType.ADDED); // M10
        return taskService.toResponse(userId, rootCopyId);
    }
}
