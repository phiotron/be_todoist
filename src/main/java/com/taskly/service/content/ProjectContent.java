package com.taskly.service.content;

import com.taskly.entity.Task;
import java.util.List;

/**
 * Nội dung chép được của một dự án (M11): task gốc không section + section đang dùng, mỗi task kèm cây con ĐANG MỞ.
 * Dùng chung cho nhân bản dự án / section (source = task gốc → chép đủ trường + label) và áp template (source = null →
 * chỉ tên, mô tả, ưu tiên).
 */
public record ProjectContent(List<NewTask> tasks, List<NewSection> sections) {

    public ProjectContent {
        tasks = List.copyOf(tasks);
        sections = List.copyOf(sections);
    }

    /** Số task kể cả sub-task. */
    public int taskCount() {
        return NewTask.count(tasks) + sections.stream().mapToInt(section -> NewTask.count(section.tasks())).sum();
    }

    /** source: task gốc khi nhân bản (chép đủ trường qua Task.copyOf); null khi tạo từ template. */
    public record NewTask(Task source, String content, String description, int priority, List<NewTask> children) {

        public NewTask {
            children = List.copyOf(children);
        }

        static int count(List<NewTask> tasks) {
            return tasks.stream().mapToInt(task -> 1 + count(task.children())).sum();
        }
    }

    public record NewSection(String name, String description, List<NewTask> tasks) {

        public NewSection {
            tasks = List.copyOf(tasks);
        }
    }
}
