package com.taskly.service;

import com.taskly.entity.ActivityEventType;
import com.taskly.entity.ActivityObjectType;
import com.taskly.entity.Project;
import com.taskly.entity.Section;
import com.taskly.entity.Task;
import com.taskly.repository.ActivityLogRepository;
import java.time.Clock;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * GHI Activity log (M10, plan F5). Service M1 gọi MỘT lần mỗi thao tác, trong cùng transaction (MANDATORY: lỗi → rollback
 * cả dòng activity). Chỉ phụ thuộc repository + Clock để không tạo vòng phụ thuộc với TaskService / ProjectService.
 * Đọc (GET activity) ở ActivityQueryService.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ActivityService {

    private final ActivityLogRepository repository;
    private final Clock clock;

    public ActivityService(ActivityLogRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Một task (tên lấy từ entity; tên dự án đọc từ DB — dự án chứa task luôn đã có trong DB). */
    public void task(UUID actorId, Task task, ActivityEventType eventType) {
        repository.insert(task.getWorkspaceId(), actorId, ActivityObjectType.TASK, task.getId(), eventType,
                task.getProjectId(), task.getContent(), null, clock.instant());
    }

    /** Mỗi task trong ids một dòng (hoàn thành / mở lại có lan truyền, bản sao của Duplicate). Rỗng → không ghi. */
    public void tasks(UUID actorId, Collection<UUID> taskIds, ActivityEventType eventType) {
        repository.insertForTasks(List.copyOf(taskIds), actorId, eventType, clock.instant());
    }

    /** Dự án (tên chụp từ entity — dự án vừa tạo có thể chưa flush). */
    public void project(UUID actorId, Project project, ActivityEventType eventType) {
        repository.insert(project.getWorkspace().getId(), actorId, ActivityObjectType.PROJECT, project.getId(),
                eventType, project.getId(), project.getName(), project.getName(), clock.instant());
    }

    /** Section; workspaceId = workspace của dự án chứa (caller đã có entity dự án). */
    public void section(UUID actorId, UUID workspaceId, Section section, ActivityEventType eventType) {
        repository.insert(workspaceId, actorId, ActivityObjectType.SECTION, section.getId(), eventType,
                section.getProjectId(), section.getName(), null, clock.instant());
    }

    /**
     * Các field mà đổi thì ghi UPDATED cho task (tên, mô tả, ưu tiên, hạn, duration, deadline — không gồm label / dự án,
     * như Todoist). Gọi trước và sau khi sửa rồi so equals.
     */
    public static List<Object> trackedFields(Task task) {
        return Arrays.asList(task.getContent(), task.getDescription(), task.getPriority(), task.getDueDate(),
                task.getDueDatetime(), task.getDurationMinutes(), task.getDeadlineDate());
    }

    /** Như trackedFields cho dự án: tên, màu, mô tả. */
    public static List<Object> trackedFields(Project project) {
        return Arrays.asList(project.getName(), project.getColor(), project.getDescription());
    }

    /** Như trackedFields cho section: tên, mô tả. */
    public static List<Object> trackedFields(Section section) {
        return Arrays.asList(section.getName(), section.getDescription());
    }
}
