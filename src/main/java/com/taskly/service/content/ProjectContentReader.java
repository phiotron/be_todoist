package com.taskly.service.content;

import com.taskly.entity.Section;
import com.taskly.entity.Task;
import com.taskly.service.SectionService;
import com.taskly.service.content.ProjectContent.NewSection;
import com.taskly.service.content.ProjectContent.NewTask;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Đọc phần CHÉP ĐƯỢC của dự án / section (M11): section chưa lưu trữ theo thứ tự; task đang mở theo (sort_order, id) —
 * task gốc + con cháu ĐANG MỞ (con đã xong không chép và cả nhánh dưới nó cũng không, như spec 5.4 / sao chép task M1).
 * Task trong section lưu trữ không chép (section đó không chép). Caller gọi trong transaction, SAU khi đã khoá dự án.
 */
@Component
public class ProjectContentReader {

    @PersistenceContext
    private EntityManager entityManager;

    private final SectionService sectionService;

    public ProjectContentReader(SectionService sectionService) {
        this.sectionService = sectionService;
    }

    public ProjectContent readProject(UUID projectId) {
        List<Section> sections = sectionService.container(projectId);
        OpenTasks open = openTasks(projectId);
        List<NewSection> newSections = sections.stream()
                .map(section -> new NewSection(section.getName(), section.getDescription(), open.roots(section.getId())))
                .toList();
        return new ProjectContent(open.roots(null), newSections);
    }

    /** Task gốc (kèm cây con đang mở) của một section. */
    public List<NewTask> readSection(UUID projectId, UUID sectionId) {
        return openTasks(projectId).roots(sectionId);
    }

    private OpenTasks openTasks(UUID projectId) {
        List<Task> tasks = entityManager.createQuery("select t from Task t where t.projectId = :projectId "
                        + "and t.completedAt is null order by t.sortOrder, t.id", Task.class)
                .setParameter("projectId", projectId)
                .getResultList();
        Set<UUID> activeSections = sectionService.container(projectId).stream().map(Section::getId)
                .collect(Collectors.toSet());
        Map<UUID, List<Task>> byParent = new HashMap<>();
        Map<UUID, List<Task>> rootsBySection = new HashMap<>(); // khoá null = không section
        for (Task task : tasks) {
            if (task.getParentId() != null) {
                byParent.computeIfAbsent(task.getParentId(), key -> new ArrayList<>()).add(task);
            } else if (task.getSectionId() == null || activeSections.contains(task.getSectionId())) {
                rootsBySection.computeIfAbsent(task.getSectionId(), key -> new ArrayList<>()).add(task);
            }
        }
        return new OpenTasks(byParent, rootsBySection);
    }

    private record OpenTasks(Map<UUID, List<Task>> byParent, Map<UUID, List<Task>> rootsBySection) {

        List<NewTask> roots(UUID sectionId) {
            return rootsBySection.getOrDefault(sectionId, List.of()).stream().map(this::node).toList();
        }

        private NewTask node(Task task) {
            List<NewTask> children = byParent.getOrDefault(task.getId(), List.of()).stream().map(this::node).toList();
            return new NewTask(task, task.getContent(), task.getDescription(), task.getPriority(), children);
        }
    }
}
