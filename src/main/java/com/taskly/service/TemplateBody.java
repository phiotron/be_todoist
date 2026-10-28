package com.taskly.service;

import com.taskly.dto.template.TemplateSectionItem;
import com.taskly.dto.template.TemplateTaskItem;
import com.taskly.service.content.ProjectContent;
import com.taskly.service.content.ProjectContent.NewSection;
import com.taskly.service.content.ProjectContent.NewTask;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * Nội dung một template (M11): task không section + section, lưu ở templates.content dạng JSON (version 1). Chỉ giữ tên,
 * mô tả, ưu tiên, cây sub-task, tên / mô tả section (plan M11, câu hỏi mở 3).
 */
public record TemplateBody(int version, List<TemplateTaskItem> tasks, List<TemplateSectionItem> sections) {

    public static final int CURRENT_VERSION = 1;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public TemplateBody {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        sections = sections == null ? List.of() : List.copyOf(sections);
    }

    public int taskCount() {
        return tasks.stream().mapToInt(TemplateTaskItem::count).sum()
                + sections.stream().mapToInt(s -> s.tasks().stream().mapToInt(TemplateTaskItem::count).sum()).sum();
    }

    public String toJson() {
        return JSON.writeValueAsString(this);
    }

    public static TemplateBody fromJson(String json) {
        return JSON.readValue(json, TemplateBody.class);
    }

    /** Ảnh chụp nội dung dự án (bỏ ngày / nhắc / label của task nguồn). */
    public static TemplateBody of(ProjectContent content) {
        return new TemplateBody(CURRENT_VERSION, items(content.tasks()), content.sections().stream()
                .map(s -> new TemplateSectionItem(s.name(), s.description(), items(s.tasks()))).toList());
    }

    /** Nội dung để ghi vào dự án (task mới, không có nguồn). */
    public ProjectContent toContent() {
        return new ProjectContent(newTasks(tasks), sections.stream()
                .map(s -> new NewSection(s.name(), s.description(), newTasks(s.tasks()))).toList());
    }

    private static List<TemplateTaskItem> items(List<NewTask> tasks) {
        return tasks.stream()
                .map(t -> new TemplateTaskItem(t.content(), t.description(), t.priority(), items(t.children())))
                .toList();
    }

    private static List<NewTask> newTasks(List<TemplateTaskItem> items) {
        return items.stream()
                .map(i -> new NewTask(null, i.content(), i.description(), i.priority(), newTasks(i.children())))
                .toList();
    }
}
