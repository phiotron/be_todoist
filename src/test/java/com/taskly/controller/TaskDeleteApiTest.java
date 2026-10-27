package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.support.TestData.TaskRow;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * DELETE tasks/{id} = xoá VĨNH VIỄN cả cây con (spec 5.7, 10.2); không có Undo; xoá lại → 404. Bản 3.1 BỎ ngoại lệ
 * "xoá được trong dự án lưu trữ": dự án lưu trữ → 409 project-archived, section lưu trữ → 409 section-archived.
 */
class TaskDeleteApiTest extends IntegrationTestBase {

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    @Test
    void delete_removesTaskAndWholeSubtree_onlyThem() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        UUID parent = testData.task(user.id(), project, "Cha");
        UUID root = testData.insert(TaskRow.of(user.id(), project, "Gốc").parent(parent));
        UUID child = testData.insert(TaskRow.of(user.id(), project, "Con").parent(root));
        UUID grandChild = testData.insert(TaskRow.of(user.id(), project, "Cháu").parent(child)
                .completedAt(clock.instant()));
        UUID sibling = testData.insert(TaskRow.of(user.id(), project, "Anh em").parent(parent));

        authDelete(user.accessToken(), "/api/v1/tasks/" + root).andExpect(status().isNoContent());

        assertThat(count("select count(*) from tasks where id in (?, ?, ?)", root, child, grandChild)).isZero();
        assertThat(count("select count(*) from tasks where id in (?, ?)", parent, sibling)).isEqualTo(2);
        authGet(user.accessToken(), "/api/v1/tasks/" + parent).andExpect(jsonPath("$.subtaskCount").value(1));
        authGet(user.accessToken(), "/api/v1/tasks/" + root).andExpect(status().isNotFound());
        authDelete(user.accessToken(), "/api/v1/tasks/" + root).andExpect(status().isNotFound());
    }

    @Test
    void delete_inArchivedSection_is409SectionArchived_andKeepsTheTask() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        UUID section = testData.section(project, "S");
        UUID inSection = testData.insert(TaskRow.of(user.id(), project, "Trong section").section(section));
        testData.archiveSection(section);

        authDelete(user.accessToken(), "/api/v1/tasks/" + inSection)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("section-archived"));

        assertThat(count("select count(*) from tasks where id = ?", inSection)).isOne();
    }

    @Test
    void delete_inArchivedProject_is409ProjectArchived_andKeepsTheTask() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        UUID task = testData.task(user.id(), project, "T");
        testData.archiveProject(project);

        authDelete(user.accessToken(), "/api/v1/tasks/" + task)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("project-archived"));

        assertThat(count("select count(*) from tasks where id = ?", task)).isOne();
    }
}
