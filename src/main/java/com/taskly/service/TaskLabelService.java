package com.taskly.service;

import com.taskly.repository.TaskLabelRepository;
import java.time.Clock;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Gắn / bỏ label trên task (task_labels). Quyền trên TASK do nơi gọi (TaskService, TaskBatchService) kiểm trước. */
@Service
@Transactional
public class TaskLabelService {

    private final TaskLabelRepository taskLabelRepository;
    private final LabelService labelService;
    private final UsageLimits usageLimits;
    private final Clock clock;

    public TaskLabelService(TaskLabelRepository taskLabelRepository, LabelService labelService,
                            UsageLimits usageLimits, Clock clock) {
        this.taskLabelRepository = taskLabelRepository;
        this.labelService = labelService;
        this.usageLimits = usageLimits;
        this.clock = clock;
    }

    /** Bỏ null / trùng (giữ thứ tự), kiểm số label / task (400) và mọi id là label của user (404). */
    @Transactional(readOnly = true)
    public List<UUID> validate(UUID userId, List<UUID> labelIds) {
        if (labelIds == null) {
            return List.of();
        }
        List<UUID> distinct = labelIds.stream().filter(Objects::nonNull).distinct().toList();
        usageLimits.checkLabelsPerTask(distinct.size());
        labelService.requireAllOwned(userId, distinct);
        return distinct;
    }

    /** Task vừa tạo (đã flush xuống DB): gắn các label đã validate. */
    public void attach(UUID taskId, List<UUID> validatedLabelIds) {
        if (!validatedLabelIds.isEmpty()) {
            taskLabelRepository.insert(taskId, validatedLabelIds);
        }
    }

    /** PATCH: thay toàn bộ tập label. Trả true nếu tập thực sự đổi. */
    public boolean replace(UUID userId, UUID taskId, List<UUID> labelIds) {
        List<UUID> wanted = validate(userId, labelIds);
        if (new HashSet<>(taskLabelRepository.labelIdsOf(taskId)).equals(new HashSet<>(wanted))) {
            return false;
        }
        taskLabelRepository.replace(taskId, wanted);
        return true;
    }

    public void touch(Collection<UUID> taskIds) {
        if (!taskIds.isEmpty()) {
            taskLabelRepository.touchTasks(taskIds, clock.instant());
        }
    }

    public void copyLabels(Map<UUID, UUID> originalToCopy) {
        if (!originalToCopy.isEmpty()) {
            taskLabelRepository.copy(originalToCopy);
        }
    }

    /** tasks/batch ADD_LABELS: task nào vượt giới hạn sau khi thêm → 400 cả lô (transaction rollback). */
    public Set<UUID> addToTasks(UUID userId, List<UUID> taskIds, List<UUID> labelIds) {
        List<UUID> wanted = validate(userId, labelIds);
        Set<UUID> changed = taskLabelRepository.addToTasks(taskIds, wanted);
        taskLabelRepository.countsFor(changed).values().forEach(usageLimits::checkLabelsPerTask);
        touch(changed);
        return changed;
    }

    /** tasks/batch REMOVE_LABELS. */
    public Set<UUID> removeFromTasks(UUID userId, List<UUID> taskIds, List<UUID> labelIds) {
        List<UUID> wanted = validate(userId, labelIds);
        Set<UUID> changed = taskLabelRepository.removeFromTasks(taskIds, wanted);
        touch(changed);
        return changed;
    }
}
