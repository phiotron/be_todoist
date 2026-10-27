package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.support.TestData.TaskRow;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Bổ sung ngoài plan Task 15: vùng chứa của `afterId` (cùng dự án + section + cha) khi tạo task. */
class TaskCreatePlacementApiTest extends IntegrationTestBase {

    private static final String TASKS = "/api/v1/tasks";

    @Test
    void afterIdOutsideTheContainer_is400_andNothingIsCreated() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        UUID section = testData.section(project, "S");
        UUID other = testData.project(user.id(), "Q");
        UUID inOtherProject = testData.insert(TaskRow.of(user.id(), other, "ở dự án khác").sortOrder(1024));
        UUID inSection = testData.insert(TaskRow.of(user.id(), project, "trong section").section(section));
        UUID parent = testData.insert(TaskRow.of(user.id(), project, "cha").sortOrder(2048));
        UUID child = testData.insert(TaskRow.of(user.id(), project, "con").parent(parent));
        UUID root = testData.insert(TaskRow.of(user.id(), project, "gốc").sortOrder(512));

        // task ở dự án khác / ở section khác / là con của task khác / không tồn tại đều không phải anh em
        authPost(user.accessToken(), TASKS, "{\"projectId\": \"%s\", \"content\": \"x\", \"afterId\": \"%s\"}"
                .formatted(project, inOtherProject)).andExpect(status().isBadRequest());
        authPost(user.accessToken(), TASKS, "{\"projectId\": \"%s\", \"content\": \"x\", \"afterId\": \"%s\"}"
                .formatted(project, inSection)).andExpect(status().isBadRequest());
        authPost(user.accessToken(), TASKS, "{\"projectId\": \"%s\", \"content\": \"x\", \"afterId\": \"%s\"}"
                .formatted(project, child)).andExpect(status().isBadRequest());
        authPost(user.accessToken(), TASKS, "{\"parentId\": \"%s\", \"content\": \"x\", \"afterId\": \"%s\"}"
                .formatted(parent, root)).andExpect(status().isBadRequest());
        authPost(user.accessToken(), TASKS, "{\"projectId\": \"%s\", \"content\": \"x\", \"afterId\": \"%s\"}"
                .formatted(project, UUID.randomUUID())).andExpect(status().isBadRequest());

        authGet(user.accessToken(), TASKS + "/" + parent).andExpect(jsonPath("$.subtaskCount").value(1));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from tasks where content = 'x'", Integer.class)).isZero();
    }

    @Test
    void subtask_afterId_placesAmongSiblingsOnly_absentGoesLast_nullGoesFirst() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        UUID parent = testData.task(user.id(), project, "Cha");
        UUID first = testData.insert(TaskRow.of(user.id(), project, "c1").parent(parent).sortOrder(1024));
        testData.insert(TaskRow.of(user.id(), project, "c2").parent(parent).sortOrder(2048));

        authPost(user.accessToken(), TASKS, "{\"parentId\": \"%s\", \"content\": \"giữa\", \"afterId\": \"%s\"}"
                .formatted(parent, first)).andExpect(jsonPath("$.sortOrder").value(1536));
        authPost(user.accessToken(), TASKS, "{\"parentId\": \"%s\", \"content\": \"cuối\"}".formatted(parent))
                .andExpect(jsonPath("$.sortOrder").value(3072));
        authPost(user.accessToken(), TASKS, "{\"parentId\": \"%s\", \"content\": \"đầu\", \"afterId\": null}"
                .formatted(parent)).andExpect(jsonPath("$.sortOrder").value(512));
    }
}
