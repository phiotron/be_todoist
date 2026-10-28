package com.taskly.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.common.error.ApiException;
import com.taskly.dto.template.TemplateSectionItem;
import com.taskly.dto.template.TemplateTaskItem;
import com.taskly.entity.ProjectColor;
import com.taskly.entity.TemplateCategory;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Thư viện template dựng sẵn (Taskly tự soạn, resources/templates/library.json): đủ 3 nhóm, mỗi chuỗi có vi + en, nằm
 * trong giới hạn Pro (≤ 20 section, ≤ 300 task, sub-task ≤ 4 cấp), màu là một trong 20 khoá.
 */
class TemplateLibraryTest {

    private static final Locale VI = Locale.forLanguageTag("vi");

    private final TemplateLibrary library = new TemplateLibrary();

    @Test
    void hasEveryCategory_andUniqueKeys() {
        Set<TemplateCategory> categories = new HashSet<>();
        Set<String> keys = new HashSet<>();
        for (TemplateLibrary.Entry entry : library.entries(VI)) {
            categories.add(entry.category());
            assertThat(keys.add(entry.key())).as("key trùng %s", entry.key()).isTrue();
            assertThat(entry.key()).matches("[a-z0-9-]{1,60}");
            ProjectColor.valueOf(entry.color());
        }
        assertThat(categories).containsExactlyInAnyOrder(TemplateCategory.values());
    }

    @Test
    void everyTextIsTranslated_andDiffersBetweenLanguages() {
        List<TemplateLibrary.Entry> vi = library.entries(VI);
        List<TemplateLibrary.Entry> en = library.entries(Locale.ENGLISH);
        assertThat(vi).hasSameSizeAs(en);
        for (int i = 0; i < vi.size(); i++) {
            assertThat(vi.get(i).name()).isNotBlank().isNotEqualTo(en.get(i).name());
            assertThat(en.get(i).name()).isNotBlank();
            assertThat(vi.get(i).description()).isNotBlank();
            assertThat(en.get(i).description()).isNotBlank();
            assertThat(vi.get(i).name().length()).isLessThanOrEqualTo(120);
            assertAllContentPresent(vi.get(i));
            assertAllContentPresent(en.get(i));
        }
    }

    @Test
    void staysWithinProLimits() {
        for (TemplateLibrary.Entry entry : library.entries(VI)) {
            assertThat(entry.body().sections().size()).isBetween(0, 20);
            assertThat(entry.taskCount()).isBetween(1, 300);
            entry.body().tasks().forEach(task -> assertDepth(task, 1));
            entry.body().sections().forEach(section -> section.tasks().forEach(task -> assertDepth(task, 1)));
        }
    }

    @Test
    void unknownKey_is404() {
        assertThatThrownBy(() -> library.require("khong-co", VI)).isInstanceOf(ApiException.class);
    }

    @Test
    void unsupportedLanguageFallsBackToVietnamese() {
        TemplateLibrary.Entry first = library.entries(VI).get(0);
        assertThat(library.require(first.key(), Locale.JAPANESE).name()).isEqualTo(first.name());
    }

    private static void assertAllContentPresent(TemplateLibrary.Entry entry) {
        entry.body().tasks().forEach(TemplateLibraryTest::assertTask);
        for (TemplateSectionItem section : entry.body().sections()) {
            assertThat(section.name()).isNotBlank();
            section.tasks().forEach(TemplateLibraryTest::assertTask);
        }
    }

    private static void assertTask(TemplateTaskItem task) {
        assertThat(task.content()).isNotBlank().hasSizeLessThanOrEqualTo(500);
        assertThat(task.priority()).isBetween(1, 4);
        task.children().forEach(TemplateLibraryTest::assertTask);
    }

    private static void assertDepth(TemplateTaskItem task, int depth) {
        assertThat(depth).isLessThanOrEqualTo(4);
        task.children().forEach(child -> assertDepth(child, depth + 1));
    }
}
