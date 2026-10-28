package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.IntegrationTestBase;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Activity log (M10, plan F5): mỗi loại sự kiện một test, lọc, phân trang, quyền, rollback. Dữ liệu dựng qua API M1
 * để chính các lời gọi ghi sự kiện chạy thật.
 */
class ActivityApiTest extends IntegrationTestBase {

    private static final String ACTIVITY = "/api/v1/activity";

    // ---- helper ----

    private UUID createProject(String token, String name) throws Exception {
        return readUuid(authPost(token, "/api/v1/projects", """
                {"name": "%s"}
                """.formatted(name)).andExpect(status().isCreated()), "$.id");
    }

    private UUID createTask(String token, UUID projectId, String content) throws Exception {
        return readUuid(authPost(token, "/api/v1/tasks", """
                {"projectId": "%s", "content": "%s"}
                """.formatted(projectId, content)).andExpect(status().isCreated()), "$.id");
    }

    private UUID createSubtask(String token, UUID parentId, String content) throws Exception {
        return readUuid(authPost(token, "/api/v1/tasks", """
                {"parentId": "%s", "content": "%s"}
                """.formatted(parentId, content)).andExpect(status().isCreated()), "$.id");
    }

    private UUID createSection(String token, UUID projectId, String name) throws Exception {
        return readUuid(authPost(token, "/api/v1/projects/" + projectId + "/sections", """
                {"name": "%s"}
                """.formatted(name)).andExpect(status().isCreated()), "$.id");
    }

    private long version(String token, UUID taskId) throws Exception {
        return readLong(authGet(token, "/api/v1/tasks/" + taskId), "$.version");
    }

    /** Các dòng activity_log của một đối tượng, cũ trước. */
    private List<Map<String, Object>> rows(UUID objectId) {
        return jdbcTemplate.queryForList("select object_type, event_type, object_name, project_name, actor_id, project_id "
                + "from activity_log where object_id = ? order by created_at, id", objectId);
    }

    private List<String> events(UUID objectId) {
        return rows(objectId).stream().map(row -> (String) row.get("event_type")).toList();
    }

    private ResultActions list(String token, String query) throws Exception {
        return authGet(token, ACTIVITY + query).andExpect(status().isOk());
    }

    // ---- task ----

    @Test
    void taskAdded_isRecordedWithNamesAndActor() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = createProject(user.accessToken(), "Công việc");
        UUID task = createTask(user.accessToken(), project, "Viết báo cáo");

        List<Map<String, Object>> rows = rows(task);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("object_type", "TASK").containsEntry("event_type", "ADDED")
                .containsEntry("object_name", "Viết báo cáo").containsEntry("project_name", "Công việc")
                .containsEntry("actor_id", user.id()).containsEntry("project_id", project);
    }

    @Test
    void taskUpdated_onlyWhenContentFieldsChange() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID task = createTask(user.accessToken(), testData.inboxOf(user.id()), "Gọi điện");

        authPatch(user.accessToken(), "/api/v1/tasks/" + task, """
                {"version": %d, "content": "Gọi điện"}
                """.formatted(version(user.accessToken(), task))).andExpect(status().isOk());
        assertThat(events(task)).containsExactly("ADDED");

        authPatch(user.accessToken(), "/api/v1/tasks/" + task, """
                {"version": %d, "content": "Gọi điện cho An", "priority": 1}
                """.formatted(version(user.accessToken(), task))).andExpect(status().isOk());
        assertThat(events(task)).containsExactly("ADDED", "UPDATED");
        assertThat(rows(task).get(1)).containsEntry("object_name", "Gọi điện cho An");
    }

    @Test
    void taskCompletedAndUncompleted_includeCascadedSubtasks() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID parent = createTask(user.accessToken(), testData.inboxOf(user.id()), "Cha");
        UUID child = createSubtask(user.accessToken(), parent, "Con");

        authPost(user.accessToken(), "/api/v1/tasks/" + parent + "/close").andExpect(status().isOk());
        assertThat(events(parent)).containsExactly("ADDED", "COMPLETED");
        assertThat(events(child)).containsExactly("ADDED", "COMPLETED");

        authPost(user.accessToken(), "/api/v1/tasks/" + parent + "/reopen", "{\"undo\": true}")
                .andExpect(status().isOk());
        assertThat(events(parent)).containsExactly("ADDED", "COMPLETED", "UNCOMPLETED");
        assertThat(events(child)).containsExactly("ADDED", "COMPLETED", "UNCOMPLETED");
    }

    @Test
    void closingAnAlreadyCompletedTask_recordsNothingMore() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID task = createTask(user.accessToken(), testData.inboxOf(user.id()), "Một lần");
        authPost(user.accessToken(), "/api/v1/tasks/" + task + "/close").andExpect(status().isOk());

        authPost(user.accessToken(), "/api/v1/tasks/" + task + "/close").andExpect(status().isOk());

        assertThat(events(task)).containsExactly("ADDED", "COMPLETED");
    }

    @Test
    void taskDeleted_keepsRowsWithSnapshotNames() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = createProject(user.accessToken(), "Nhà");
        UUID task = createTask(user.accessToken(), project, "Dọn phòng");

        authDelete(user.accessToken(), "/api/v1/tasks/" + task).andExpect(status().isNoContent());

        assertThat(events(task)).containsExactly("ADDED", "DELETED");
        assertThat(rows(task).get(1)).containsEntry("object_name", "Dọn phòng").containsEntry("project_name", "Nhà");
    }

    @Test
    void duplicate_recordsAddedForEveryCopy() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID parent = createTask(user.accessToken(), testData.inboxOf(user.id()), "Gốc");
        createSubtask(user.accessToken(), parent, "Con");

        UUID copy = readUuid(authPost(user.accessToken(), "/api/v1/tasks/" + parent + "/duplicate")
                .andExpect(status().isCreated()), "$.id");

        assertThat(events(copy)).containsExactly("ADDED");
        assertThat(jdbcTemplate.queryForObject("select count(*) from activity_log a join tasks t on t.id = a.object_id "
                + "where t.parent_id = ? and a.event_type = 'ADDED'", Integer.class, copy)).isEqualTo(1);
    }

    @Test
    void batch_closeScheduleAndPriority_recordEvents() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID inbox = testData.inboxOf(user.id());
        UUID a = createTask(user.accessToken(), inbox, "A");
        UUID b = createTask(user.accessToken(), inbox, "B");

        authPost(user.accessToken(), "/api/v1/tasks/batch", """
                {"action": "PRIORITY", "taskIds": ["%s", "%s"], "priority": 2}
                """.formatted(a, b)).andExpect(status().isOk());
        authPost(user.accessToken(), "/api/v1/tasks/batch", """
                {"action": "SCHEDULE", "taskIds": ["%s"], "date": "2030-01-02"}
                """.formatted(a)).andExpect(status().isOk());
        authPost(user.accessToken(), "/api/v1/tasks/batch", """
                {"action": "CLOSE", "taskIds": ["%s", "%s"]}
                """.formatted(a, b)).andExpect(status().isOk());
        authPost(user.accessToken(), "/api/v1/tasks/batch", """
                {"action": "REOPEN", "taskIds": ["%s"]}
                """.formatted(b)).andExpect(status().isOk());

        assertThat(events(a)).containsExactly("ADDED", "UPDATED", "UPDATED", "COMPLETED");
        assertThat(events(b)).containsExactly("ADDED", "UPDATED", "COMPLETED", "UNCOMPLETED");
    }

    @Test
    void batch_priorityUnchanged_recordsNothing() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID a = createTask(user.accessToken(), testData.inboxOf(user.id()), "A");

        authPost(user.accessToken(), "/api/v1/tasks/batch", """
                {"action": "PRIORITY", "taskIds": ["%s"], "priority": 4}
                """.formatted(a)).andExpect(status().isOk());

        assertThat(events(a)).containsExactly("ADDED");
    }

    @Test
    void batch_delete_recordsDeletedForRoots() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID a = createTask(user.accessToken(), testData.inboxOf(user.id()), "A");

        authPost(user.accessToken(), "/api/v1/tasks/batch", """
                {"action": "DELETE", "taskIds": ["%s"]}
                """.formatted(a)).andExpect(status().isOk());

        assertThat(events(a)).containsExactly("ADDED", "DELETED");
    }

    @Test
    void move_isNotRecorded() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID target = createProject(user.accessToken(), "Đích");
        UUID task = createTask(user.accessToken(), testData.inboxOf(user.id()), "Chuyển đi");

        authPost(user.accessToken(), "/api/v1/tasks/" + task + "/move", """
                {"projectId": "%s"}
                """.formatted(target)).andExpect(status().isOk());

        assertThat(events(task)).containsExactly("ADDED");
    }

    // ---- dự án ----

    @Test
    void projectLifecycle_recordsAddedUpdatedArchivedUnarchivedDeleted() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = createProject(user.accessToken(), "Dự án A");

        authPatch(user.accessToken(), "/api/v1/projects/" + project, """
                {"name": "Dự án A"}
                """).andExpect(status().isOk()); // không đổi → không ghi
        authPatch(user.accessToken(), "/api/v1/projects/" + project, """
                {"name": "Dự án B", "color": "blue"}
                """).andExpect(status().isOk());
        authPost(user.accessToken(), "/api/v1/projects/" + project + "/archive").andExpect(status().isOk());
        authPost(user.accessToken(), "/api/v1/projects/" + project + "/archive").andExpect(status().isOk()); // lặp
        authPost(user.accessToken(), "/api/v1/projects/" + project + "/unarchive").andExpect(status().isOk());
        authDelete(user.accessToken(), "/api/v1/projects/" + project).andExpect(status().isNoContent());

        assertThat(events(project)).containsExactly("ADDED", "UPDATED", "ARCHIVED", "UNARCHIVED", "DELETED");
        List<Map<String, Object>> rows = rows(project);
        assertThat(rows.get(0)).containsEntry("object_type", "PROJECT").containsEntry("object_name", "Dự án A")
                .containsEntry("project_id", project);
        assertThat(rows.get(4)).containsEntry("object_name", "Dự án B");
    }

    // ---- section ----

    @Test
    void sectionLifecycle_recordsAddedUpdatedArchivedUnarchivedDeleted() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = createProject(user.accessToken(), "Có section");
        UUID section = createSection(user.accessToken(), project, "Tuần 1");
        String path = "/api/v1/projects/" + project + "/sections/" + section;

        authPatch(user.accessToken(), path, """
                {"name": "Tuần 2"}
                """).andExpect(status().isOk());
        authPost(user.accessToken(), path + "/archive").andExpect(status().isOk());
        authPost(user.accessToken(), path + "/unarchive").andExpect(status().isOk());
        authDelete(user.accessToken(), path).andExpect(status().isNoContent());

        assertThat(events(section)).containsExactly("ADDED", "UPDATED", "ARCHIVED", "UNARCHIVED", "DELETED");
        assertThat(rows(section).get(0)).containsEntry("object_type", "SECTION")
                .containsEntry("project_name", "Có section").containsEntry("project_id", project);
    }

    // ---- GET activity ----

    @Test
    void list_newestFirst_withFields() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = createProject(user.accessToken(), "Danh sách");
        clock.advance(Duration.ofSeconds(1));
        UUID task = createTask(user.accessToken(), project, "Đầu tiên");
        clock.advance(Duration.ofSeconds(1));
        authPost(user.accessToken(), "/api/v1/tasks/" + task + "/close").andExpect(status().isOk());

        list(user.accessToken(), "")
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].eventType").value("COMPLETED"))
                .andExpect(jsonPath("$.items[0].objectType").value("TASK"))
                .andExpect(jsonPath("$.items[0].objectId").value(task.toString()))
                .andExpect(jsonPath("$.items[0].objectName").value("Đầu tiên"))
                .andExpect(jsonPath("$.items[0].projectId").value(project.toString()))
                .andExpect(jsonPath("$.items[0].projectName").value("Danh sách"))
                .andExpect(jsonPath("$.items[0].initiatorId").value(user.id().toString()))
                .andExpect(jsonPath("$.items[0].createdAt").exists())
                .andExpect(jsonPath("$.items[1].eventType").value("ADDED"))
                .andExpect(jsonPath("$.items[2].objectType").value("PROJECT"))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    @Test
    void list_filtersByProjectTaskTypeEventAndInitiator() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p1 = createProject(user.accessToken(), "P1");
        UUID p2 = createProject(user.accessToken(), "P2");
        UUID t1 = createTask(user.accessToken(), p1, "T1");
        createTask(user.accessToken(), p2, "T2");
        authPost(user.accessToken(), "/api/v1/tasks/" + t1 + "/close").andExpect(status().isOk());

        list(user.accessToken(), "?projectId=" + p1).andExpect(jsonPath("$.items", hasSize(3))); // P1 + T1 ×2
        list(user.accessToken(), "?taskId=" + t1).andExpect(jsonPath("$.items", hasSize(2)));
        list(user.accessToken(), "?objectType=project").andExpect(jsonPath("$.items", hasSize(2)));
        list(user.accessToken(), "?eventType=completed")
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].objectId").value(t1.toString()));
        list(user.accessToken(), "?projectId=" + p1 + "&eventType=added&objectType=task")
                .andExpect(jsonPath("$.items", hasSize(1)));
        list(user.accessToken(), "?initiatorId=" + user.id()).andExpect(jsonPath("$.items", hasSize(5)));
        list(user.accessToken(), "?initiatorId=" + UUID.randomUUID()).andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void list_paginatesWithCursor() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID inbox = testData.inboxOf(user.id());
        for (int i = 0; i < 5; i++) {
            clock.advance(Duration.ofMillis(10));
            createTask(user.accessToken(), inbox, "Việc " + i);
        }

        String first = body(list(user.accessToken(), "?limit=2")
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].objectName").value("Việc 4"))
                .andReturn());
        String cursor = JsonPath.read(first, "$.nextCursor");
        String second = body(list(user.accessToken(), "?limit=2&cursor=" + cursor)
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].objectName").value("Việc 2"))
                .andReturn());
        String cursor2 = JsonPath.read(second, "$.nextCursor");
        list(user.accessToken(), "?limit=2&cursor=" + cursor2)
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].objectName").value("Việc 0"))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    @Test
    void list_onlyShowsOwnWorkspaces_andOtherUsersFiltersAre404() throws Exception {
        RegisteredUser owner = registerUser(uniqueEmail());
        RegisteredUser other = registerUser(uniqueEmail());
        UUID project = createProject(owner.accessToken(), "Riêng");
        UUID task = createTask(owner.accessToken(), project, "Bí mật");

        list(other.accessToken(), "").andExpect(jsonPath("$.items", hasSize(0)));
        authGet(other.accessToken(), ACTIVITY + "?projectId=" + project).andExpect(status().isNotFound());
        authGet(other.accessToken(), ACTIVITY + "?taskId=" + task).andExpect(status().isNotFound());
    }

    @Test
    void list_rejectsUnknownFilterValuesAndBadCursor() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        authGet(user.accessToken(), ACTIVITY + "?eventType=moved").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
        authGet(user.accessToken(), ACTIVITY + "?eventType=COMPLETED").andExpect(status().isBadRequest());
        authGet(user.accessToken(), ACTIVITY + "?objectType=label").andExpect(status().isBadRequest());
        authGet(user.accessToken(), ACTIVITY + "?cursor=abc").andExpect(status().isBadRequest());
        authGet(user.accessToken(), ACTIVITY + "?limit=0").andExpect(status().isBadRequest());
        authGet(user.accessToken(), ACTIVITY + "?projectId=not-a-uuid").andExpect(status().isBadRequest());
    }

    @Test
    void failedOperation_leavesNoActivityRow() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = createProject(user.accessToken(), "Lưu trữ");
        authPost(user.accessToken(), "/api/v1/projects/" + project + "/archive").andExpect(status().isOk());
        int before = jdbcTemplate.queryForObject("select count(*) from activity_log where project_id = ?",
                Integer.class, project);

        authPost(user.accessToken(), "/api/v1/tasks", """
                {"projectId": "%s", "content": "Không vào được"}
                """.formatted(project)).andExpect(status().isUnprocessableEntity());

        assertThat(jdbcTemplate.queryForObject("select count(*) from activity_log where project_id = ?",
                Integer.class, project)).isEqualTo(before);
    }

    @Test
    void deletedTask_activityStillListedByProject() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = createProject(user.accessToken(), "Còn dấu");
        UUID task = createTask(user.accessToken(), project, "Sẽ xoá");
        authDelete(user.accessToken(), "/api/v1/tasks/" + task).andExpect(status().isNoContent());

        list(user.accessToken(), "?projectId=" + project + "&eventType=deleted")
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].objectName").value("Sẽ xoá"));
    }
}
