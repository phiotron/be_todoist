package com.taskly.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Thay đổi theo syncToken (M9 plan F3): full / incremental, tombstone, native UPDATE, task_labels, token sai / cũ. */
class SyncChangesApiTest extends SyncTestBase {

    private static List<String> deletedIds(String body, String type) {
        return JsonPath.read(body, "$.deleted[?(@.type=='" + type + "')].id");
    }

    private String since(String token, String syncToken) throws Exception {
        return syncOk(token, "{\"syncToken\": \"" + syncToken + "\"}");
    }

    @Test
    void incremental_returnsOnlyWhatChangedSinceTheToken() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        UUID untouched = createTask(token, "{\"content\": \"giữ nguyên\"}");
        String syncToken = freshToken(token);

        UUID created = createTask(token, "{\"content\": \"mới\"}");
        String body = since(token, syncToken);

        assertThat((Boolean) JsonPath.read(body, "$.fullSync")).isFalse();
        assertThat(idsOf(body, "tasks")).contains(created.toString()).doesNotContain(untouched.toString());
        assertThat(JsonPath.<List<?>>read(body, "$.deleted")).isEmpty();

        // token mới sau khi không có gì đổi: các bản ghi cũ không quay lại
        String next = JsonPath.read(body, "$.syncToken");
        assertThat(idsOf(since(token, next), "tasks")).doesNotContain(untouched.toString());
    }

    @Test
    void update_andComplete_appearInChanges_andReorderNativeUpdateToo() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        UUID a = createTask(token, "{\"content\": \"A\"}");
        UUID b = createTask(token, "{\"content\": \"B\"}");
        String syncToken = freshToken(token);

        authPatchOk(token, a, "{\"version\": 0, \"content\": \"A2\"}");
        apiPost(token, "/api/v1/tasks/" + b + "/close", null);
        String body = since(token, syncToken);

        assertThat(idsOf(body, "tasks")).contains(a.toString(), b.toString());
        List<String> done = JsonPath.read(body, "$.changes.tasks[?(@.id=='" + b + "')].completedAt");
        assertThat(done.get(0)).isNotNull();

        // sắp xếp = native UPDATE: JPA không thấy nhưng trigger thấy
        String token2 = freshToken(token);
        apiPost(token, "/api/v1/reorder", "{\"type\": \"TASK\", \"id\": \"" + a + "\", \"afterId\": null}").andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertThat(idsOf(since(token, token2), "tasks")).isNotEmpty();
    }

    private void authPatchOk(String token, UUID taskId, String json) throws Exception {
        apiPatch(token, "/api/v1/tasks/" + taskId, json).andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    @Test
    void permanentDelete_leavesTombstones_includingCascade() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        UUID project = createProject(token, "P");
        UUID section = createSection(token, project, "S");
        UUID parent = createTask(token, "{\"content\": \"cha\", \"projectId\": \"" + project + "\", \"sectionId\": \"" + section + "\"}");
        UUID child = createTask(token, "{\"content\": \"con\", \"parentId\": \"" + parent + "\"}");
        UUID standalone = createTask(token, "{\"content\": \"lẻ\"}");
        UUID label = idOf(apiPost(token, "/api/v1/labels", "{\"name\": \"l\"}"));
        UUID filter = idOf(apiPost(token, "/api/v1/filters", "{\"name\": \"f\", \"query\": \"p1\"}"));
        String syncToken = freshToken(token);

        apiDelete(token, "/api/v1/tasks/" + standalone).andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
        apiDelete(token, "/api/v1/projects/" + project).andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
        apiDelete(token, "/api/v1/labels/" + label);
        apiDelete(token, "/api/v1/filters/" + filter);
        String body = since(token, syncToken);

        assertThat(deletedIds(body, "TASK")).contains(standalone.toString(), parent.toString(), child.toString());
        assertThat(deletedIds(body, "SECTION")).contains(section.toString());
        assertThat(deletedIds(body, "PROJECT")).contains(project.toString());
        assertThat(deletedIds(body, "LABEL")).contains(label.toString());
        assertThat(deletedIds(body, "FILTER")).contains(filter.toString());
        assertThat(idsOf(body, "tasks")).doesNotContain(parent.toString(), child.toString());

        // tạo lại cùng id sau khi xoá → hồi sinh, không còn tombstone
        String revive = syncOk(token, request(syncToken,
                cmd(UUID.randomUUID(), "label_add", label.toString(), "{\"name\": \"l\"}")));
        assertThat(deletedIds(revive, "LABEL")).doesNotContain(label.toString());
        assertThat(idsOf(revive, "labels")).contains(label.toString());
    }

    @Test
    void labelOfTaskChanged_bumpsTheTask() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        UUID label = idOf(apiPost(token, "/api/v1/labels", "{\"name\": \"l\"}"));
        UUID task = createTask(token, "{\"content\": \"T\"}");
        String syncToken = freshToken(token);

        apiPatch(token, "/api/v1/tasks/" + task, "{\"version\": 0, \"labelIds\": [\"" + label + "\"]}");
        // chỉ xoá label → task_labels bị xoá lan: task phải được báo đổi (nhãn mất khỏi labelIds)
        String body = since(token, syncToken);
        assertThat(idsOf(body, "tasks")).contains(task.toString());

        String second = JsonPath.read(body, "$.syncToken");
        apiDelete(token, "/api/v1/labels/" + label);
        String afterDelete = since(token, second);
        assertThat(idsOf(afterDelete, "tasks")).contains(task.toString());
        assertThat(deletedIds(afterDelete, "LABEL")).contains(label.toString());
    }

    @Test
    void favoriteChange_bumpsTheProject() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        UUID project = createProject(token, "P");
        String syncToken = freshToken(token);

        apiPut(token, "/api/v1/projects/" + project + "/favorite", null);
        assertThat(idsOf(since(token, syncToken), "projects")).contains(project.toString());
    }

    @Test
    void resourceTypes_filtersWhatIsReturned() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        createTask(token, "{\"content\": \"T\"}");
        idOf(apiPost(token, "/api/v1/labels", "{\"name\": \"l\"}"));

        String body = syncOk(token, "{\"resourceTypes\": [\"labels\"]}");
        assertThat(JsonPath.<java.util.Map<String, ?>>read(body, "$.changes")).containsOnlyKeys("labels");
        assertThat(idsOf(body, "labels")).hasSize(1);
    }

    @Test
    void doesNotLeakOtherUsersChanges() throws Exception {
        String alice = registerUser(uniqueEmail()).accessToken();
        String bob = registerUser(uniqueEmail()).accessToken();
        String bobToken = freshToken(bob);

        UUID aliceTask = createTask(alice, "{\"content\": \"của alice\"}");
        UUID aliceLabel = idOf(apiPost(alice, "/api/v1/labels", "{\"name\": \"al\"}"));
        apiDelete(alice, "/api/v1/tasks/" + aliceTask);
        String body = since(bob, bobToken);

        assertThat(idsOf(body, "tasks")).doesNotContain(aliceTask.toString());
        assertThat(idsOf(body, "labels")).doesNotContain(aliceLabel.toString());
        assertThat(deletedIds(body, "TASK")).doesNotContain(aliceTask.toString());
    }

    @Test
    void invalidAndExpiredTokens() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        UUID task = createTask(token, "{\"content\": \"T\"}");

        sync(token, "{\"syncToken\": \"1.2.3\"}").andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        // token hợp lệ nhưng phát hành quá 30 ngày → full sync
        String old = "999999999999." + (clock.instant().minus(Duration.ofDays(31)).getEpochSecond());
        String body = since(token, old);
        assertThat((Boolean) JsonPath.read(body, "$.fullSync")).isTrue();
        assertThat(idsOf(body, "tasks")).contains(task.toString());
    }

    @Test
    void accountDeletion_leavesNoSyncRows() throws Exception {
        var user = registerUser(uniqueEmail());
        UUID task = createTask(user.accessToken(), "{\"content\": \"T\"}");
        UUID label = idOf(apiPost(user.accessToken(), "/api/v1/labels", "{\"name\": \"l\"}"));
        syncOk(user.accessToken(), request("*", cmd("label_add", "{\"name\": \"m\"}")));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/me")
                        .header("Authorization", bearer(user.accessToken()))
                        .contentType("application/json").content("{\"password\": \"" + FIXTURE_PASSWORD + "\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());

        assertThat(jdbcTemplate.queryForObject("select count(*) from sync_changes where entity_id in (?, ?)",
                Integer.class, task, label)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from sync_commands where user_id = ?",
                Integer.class, user.id())).isZero();
    }

    @Test
    void cleanupJob_dropsOldTombstonesAndCommands() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        UUID task = createTask(token, "{\"content\": \"T\"}");
        apiDelete(token, "/api/v1/tasks/" + task);
        UUID commandUuid = UUID.randomUUID();
        syncOk(token, request("*", cmd(commandUuid, "label_add", null, "{\"name\": \"zz\"}")));
        jdbcTemplate.update("update sync_changes set changed_at = now() - interval '40 days' where entity_id = ?", task);
        jdbcTemplate.update("update sync_commands set created_at = now() - interval '40 days' where command_uuid = ?",
                commandUuid);

        syncCleanupJob.purge();

        assertThat(jdbcTemplate.queryForObject("select count(*) from sync_changes where entity_id = ?", Integer.class, task))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from sync_commands where command_uuid = ?",
                Integer.class, commandUuid)).isZero();
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.taskly.service.sync.SyncCleanupJob syncCleanupJob;
}
