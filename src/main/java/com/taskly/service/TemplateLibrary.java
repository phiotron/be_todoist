package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.dto.template.TemplateSectionItem;
import com.taskly.dto.template.TemplateTaskItem;
import com.taskly.entity.TemplateCategory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Thư viện template dựng sẵn (M11 "Browse templates"): nội dung do Taskly TỰ SOẠN (không chép template của Todoist),
 * nằm ở resources/templates/library.json, mỗi chuỗi có "vi" + "en"; ngôn ngữ khác → "vi". Nạp một lần lúc khởi động.
 */
@Component
public class TemplateLibrary {

    static final String RESOURCE = "/templates/library.json";
    private static final String FALLBACK_LANGUAGE = "vi";

    /** Template thư viện đã dịch theo một ngôn ngữ. */
    public record Entry(String key, TemplateCategory category, String color, String name, String description,
                        TemplateBody body) {

        public int sectionCount() {
            return body.sections().size();
        }

        public int taskCount() {
            return body.taskCount();
        }
    }

    record LibFile(List<LibEntry> templates) {
    }

    record LibEntry(String key, TemplateCategory category, String color, Map<String, String> name,
                    Map<String, String> description, List<LibTask> tasks, List<LibSection> sections) {
    }

    record LibTask(Map<String, String> content, Map<String, String> description, Integer priority,
                   List<LibTask> children) {
    }

    record LibSection(Map<String, String> name, Map<String, String> description, List<LibTask> tasks) {
    }

    private final List<LibEntry> entries;

    public TemplateLibrary() {
        try (InputStream in = TemplateLibrary.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Thiếu " + RESOURCE);
            }
            entries = List.copyOf(JsonMapper.builder().build().readValue(in, LibFile.class).templates());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Mọi template theo thứ tự trong file. */
    public List<Entry> entries(Locale locale) {
        String language = language(locale);
        return entries.stream().map(entry -> localize(entry, language)).toList();
    }

    /** Template theo key; không có → 404. */
    public Entry require(String key, Locale locale) {
        String language = language(locale);
        return entries.stream()
                .filter(entry -> entry.key().equals(key))
                .findFirst()
                .map(entry -> localize(entry, language))
                .orElseThrow(() -> ApiException.notFound(ErrorMessage.TEMPLATE_NOT_FOUND));
    }

    private static String language(Locale locale) {
        return locale != null && "en".equals(locale.getLanguage()) ? "en" : FALLBACK_LANGUAGE;
    }

    private static Entry localize(LibEntry entry, String language) {
        TemplateBody body = new TemplateBody(TemplateBody.CURRENT_VERSION, tasks(entry.tasks(), language),
                entry.sections() == null ? List.of() : entry.sections().stream()
                        .map(s -> new TemplateSectionItem(text(s.name(), language), text(s.description(), language),
                                tasks(s.tasks(), language)))
                        .toList());
        return new Entry(entry.key(), entry.category(), entry.color(), text(entry.name(), language),
                text(entry.description(), language), body);
    }

    private static List<TemplateTaskItem> tasks(List<LibTask> tasks, String language) {
        if (tasks == null) {
            return List.of();
        }
        return tasks.stream()
                .map(t -> new TemplateTaskItem(text(t.content(), language), text(t.description(), language),
                        t.priority() == null ? 4 : t.priority(), tasks(t.children(), language)))
                .toList();
    }

    private static String text(Map<String, String> texts, String language) {
        if (texts == null) {
            return "";
        }
        String value = texts.get(language);
        return value != null ? value : texts.getOrDefault(FALLBACK_LANGUAGE, "");
    }
}
