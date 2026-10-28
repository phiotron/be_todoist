package com.taskly.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.entity.LimitKind;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** POST /api/v1/sync — lệnh (M9 plan F2): từng loại lệnh, chống gửi trùng, tempId, lỗi từng lệnh, quyền, giới hạn. */
class SyncApiTest extends SyncTestBase {

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    @Test
    void requiresAuthentication() throws Exception {
        postJson(SYNC, "{}").andExpect(status().isUnauthorized());
    }

    @Test
    void emptyRequest_returnsTokenAndFullSyncOfOwnData() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        UUID taskId = createTask(token, "{\"content\": \"có sẵn\"}");

        String body = syncOk(token, "{}");

        assertThat((Boolean) JsonPath.read(body, "$.fullSync")).isTrue();
        assertThat((String) JsonPath.read(body, "$.syncToken")).matches("\\d+\\.\\d+");
        assertThat(idsOf(body, "tasks")).contains(taskId.toString());
        assertThat(idsOf(body, "projects")).isNotEmpty(); // Inbox
        assertThat(JsonPath.<List<?>>read(body, "$.results")).isEmpty();
    }

    @Test
    void addCommands_createRecordsWithClientIds_andTempIdMapping() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID labelId = UUID.randomUUID();
        UUID filterId = UUID.randomUUID();
        String body = syncOk(token, request("*",
                cmd(UUID.randomUUID(), "project_add", projectId.toString(), "{\"name\": \"Dự án offline\"}"),
                cmd(UUID.randomUUID(), "task_add", taskId.toString(),
                        "{\"content\": \"việc offline\", \"projectId\": \"" + projectId + "\", \"priority\": 2}"),
                cmd(UUID.randomUUID(), "label_add", labelId.toString(), "{\"name\": \"nhan\"}"),
                cmd(UUID.randomUUID(), "filter_add", filterId.toString(), "{\"name\": \"F\", \"query\": \"p1\"}")));

        assertThat(okStatuses(body)).isEqualTo("ok,ok,ok,ok");
        assertThat((String) JsonPath.read(body, "$.tempIdMapping['" + taskId + "']")).isEqualTo(taskId.toString());
        assertThat(idsOf(body, "tasks")).contains(taskId.toString());
        assertThat(idsOf(body, "projects")).contains(projectId.toString());
        assertThat(idsOf(body, "labels")).contains(labelId.toString());
        assertThat(idsOf(body, "filters")).contains(filterId.toString());
        apiGet(token, "/api/v1/tasks/" + taskId).andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(projectId.toString()))
                .andExpect(jsonPath("$.priority").value(2));
    }

    @Test
    void nonUuidTempId_isMappedToServerGeneratedId_andUsedByLaterCommands() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        String body = syncOk(token, request("*",
                cmd(UUID.randomUUID(), "project_add", "tmp-p", "{\"name\": \"P\"}"),
                cmd(UUID.randomUUID(), "section_add", "tmp-s", "{\"projectId\": \"tmp-p\", \"name\": \"S\"}"),
                cmd(UUID.randomUUID(), "task_add", "tmp-t",
                        "{\"content\": \"T\", \"projectId\": \"tmp-p\", \"sectionId\": \"tmp-s\"}")));

        assertThat(okStatuses(body)).isEqualTo("ok,ok,ok");
        String projectId = JsonPath.read(body, "$.tempIdMapping['tmp-p']");
        String sectionId = JsonPath.read(body, "$.tempIdMapping['tmp-s']");
        String taskId = JsonPath.read(body, "$.tempIdMapping['tmp-t']");
        assertThat(UUID.fromString(projectId)).isNotNull();
        apiGet(token, "/api/v1/tasks/" + taskId).andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(projectId))
                .andExpect(jsonPath("$.sectionId").value(sectionId));
    }

    @Test
    void sameUuidTwice_runsOnce_andSecondReturnsOkWithStoredMapping() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        UUID commandUuid = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        String add = cmd(commandUuid, "task_add", taskId.toString(), "{\"content\": \"một lần\"}");

        syncOk(token, request("*", add));
        String second = syncOk(token, request("*", add));

        assertThat(okStatuses(second)).isEqualTo("ok");
        assertThat((String) JsonPath.read(second, "$.tempIdMapping['" + taskId + "']")).isEqualTo(taskId.toString());
        assertThat(count("select count(*) from tasks where id = ?", taskId)).isOne();
        assertThat(count("select count(*) from sync_commands where command_uuid = ?", commandUuid)).isOne();
    }

    @Test
    void duplicateUuidInsideOneRequest_isExecutedOnce() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        UUID commandUuid = UUID.randomUUID();
        String add = cmd(commandUuid, "label_add", null, "{\"name\": \"x\"}");

        String body = syncOk(token, request("*", add, add));

        assertThat(okStatuses(body)).isEqualTo("ok,ok");
        assertThat(JsonPath.<List<?>>read(getOk(token, "/api/v1/labels"), "$")).hasSize(1);
    }

    @Test
    void sameCommandUuidSentConcurrently_createsOnlyOneRecord() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        UUID taskId = UUID.randomUUID();
        String json = request("*", cmd(UUID.randomUUID(), "task_add", taskId.toString(), "{\"content\": \"đua\"}"));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            List<java.util.concurrent.Future<String>> calls = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                calls.add(pool.submit(() -> okStatuses(syncOk(token, json))));
            }
            for (var call : calls) {
                assertThat(call.get()).isEqualTo("ok");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("select count(*) from tasks where id = ?", taskId)).isOne();
    }

    @Test
    void failedCommand_isReportedPerCommand_andDoesNotStopTheBatch() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        UUID failing = UUID.randomUUID();
        UUID goodTask = UUID.randomUUID();

        String body = syncOk(token, request("*",
                cmd(failing, "task_add", null, "{\"content\": \"\"}"),
                cmd(UUID.randomUUID(), "bogus_command", null, "{}"),
                cmd(UUID.randomUUID(), "task_delete", null, "{\"id\": \"" + UUID.randomUUID() + "\"}"),
                cmd(UUID.randomUUID(), "task_add", goodTask.toString(), "{\"content\": \"vẫn tạo\"}")));

        assertThat(okStatuses(body)).isEqualTo("error,error,error,ok");
        assertThat((Integer) JsonPath.read(body, "$.results[0].error.status")).isEqualTo(400);
        assertThat((String) JsonPath.read(body, "$.results[0].error.errors[0].field")).isEqualTo("content");
        assertThat((Integer) JsonPath.read(body, "$.results[1].error.status")).isEqualTo(400);
        assertThat((String) JsonPath.read(body, "$.results[1].error.code")).isEqualTo("sync-command-unknown");
        assertThat((Integer) JsonPath.read(body, "$.results[2].error.status")).isEqualTo(404);
        assertThat(idsOf(body, "tasks")).contains(goodTask.toString());
        // lệnh lỗi KHÔNG chiếm uuid → gửi lại bản đã sửa với cùng uuid được
        assertThat(count("select count(*) from sync_commands where command_uuid = ?", failing)).isZero();
        String retry = syncOk(token, request("*", cmd(failing, "task_add", null, "{\"content\": \"đã sửa\"}")));
        assertThat(okStatuses(retry)).isEqualTo("ok");
    }

    @Test
    void errorTextsFollowAcceptLanguage() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        String json = request("*", cmd("bogus", "{}"));
        String vi = JsonPath.read(body(sync(token, json).andReturn()), "$.results[0].error.detail");
        String en = JsonPath.read(body(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post(SYNC).header("Authorization", bearer(token)).header("Accept-Language", "en")
                        .contentType("application/json").content(json)).andReturn()), "$.results[0].error.detail");
        assertThat(vi).isNotEqualTo(en);
        assertThat(en).isEqualTo("This sync command type is not supported");
    }

    @Test
    void updateCloseReopenMoveDeleteCommands_workOnTasks() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        UUID projectId = createProject(token, "Đích");
        UUID taskId = createTask(token, "{\"content\": \"gốc\"}");

        String body = syncOk(token, request("*",
                cmd("task_update", "{\"id\": \"" + taskId + "\", \"content\": \"đã sửa\", \"priority\": 1}"),
                cmd("task_move", "{\"id\": \"" + taskId + "\", \"projectId\": \"" + projectId + "\"}"),
                cmd("task_close", "{\"id\": \"" + taskId + "\"}"),
                cmd("task_reopen", "{\"id\": \"" + taskId + "\"}")));

        assertThat(okStatuses(body)).isEqualTo("ok,ok,ok,ok");
        apiGet(token, "/api/v1/tasks/" + taskId).andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("đã sửa"))
                .andExpect(jsonPath("$.priority").value(1))
                .andExpect(jsonPath("$.projectId").value(projectId.toString()))
                .andExpect(jsonPath("$.completedAt").value((Object) null));

        syncOk(token, request("*", cmd("task_delete", "{\"id\": \"" + taskId + "\"}")));
        apiGet(token, "/api/v1/tasks/" + taskId).andExpect(status().isNotFound());
    }

    @Test
    void projectAndSectionCommands_workEndToEnd() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        UUID projectId = createProject(token, "P");
        UUID parentId = createProject(token, "Cha");
        UUID sectionId = createSection(token, projectId, "S");

        String body = syncOk(token, request("*",
                cmd("project_update", "{\"id\": \"" + projectId + "\", \"name\": \"P2\", \"color\": \"teal\"}"),
                cmd("project_move", "{\"id\": \"" + projectId + "\", \"parentId\": \"" + parentId + "\"}"),
                cmd("section_update", "{\"id\": \"" + sectionId + "\", \"name\": \"S2\"}"),
                cmd("section_archive", "{\"id\": \"" + sectionId + "\"}"),
                cmd("section_unarchive", "{\"id\": \"" + sectionId + "\", \"projectId\": \"" + projectId + "\"}"),
                cmd("section_add", "{\"projectId\": \"" + projectId + "\", \"name\": \"S3\"}"),
                cmd("project_archive", "{\"id\": \"" + projectId + "\"}"),
                cmd("project_unarchive", "{\"id\": \"" + projectId + "\"}")));

        assertThat(okStatuses(body)).isEqualTo("ok,ok,ok,ok,ok,ok,ok,ok");
        apiGet(token, "/api/v1/projects/" + projectId).andExpect(jsonPath("$.name").value("P2"))
                .andExpect(jsonPath("$.parentId").value(parentId.toString()));

        syncOk(token, request("*",
                cmd("section_delete", "{\"id\": \"" + sectionId + "\"}"),
                cmd("project_delete", "{\"id\": \"" + parentId + "\"}")));
        assertThat(count("select count(*) from sections where id = ?", sectionId)).isZero();
        assertThat(count("select count(*) from projects where id in (?, ?)", parentId, projectId)).isZero();
    }

    @Test
    void labelAndFilterCommands_updateAndDelete() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        UUID labelId = idOf(apiPost(token, "/api/v1/labels", "{\"name\": \"a\"}"));
        UUID filterId = idOf(apiPost(token, "/api/v1/filters", "{\"name\": \"f\", \"query\": \"p1\"}"));

        String body = syncOk(token, request("*",
                cmd("label_update", "{\"id\": \"" + labelId + "\", \"name\": \"b\", \"color\": \"red\"}"),
                cmd("filter_update", "{\"id\": \"" + filterId + "\", \"name\": \"g\"}")));
        assertThat(okStatuses(body)).isEqualTo("ok,ok");
        assertThat(getOk(token, "/api/v1/labels")).contains("\"b\"");

        syncOk(token, request("*",
                cmd("label_delete", "{\"id\": \"" + labelId + "\"}"),
                cmd("filter_delete", "{\"id\": \"" + filterId + "\"}")));
        assertThat(getOk(token, "/api/v1/labels")).isEqualTo("[]");
        assertThat(getOk(token, "/api/v1/filters")).isEqualTo("[]");
    }

    @Test
    void anotherUsersRecords_areNotReachable() throws Exception {
        String owner = registerUser(uniqueEmail()).accessToken();
        String other = registerUser(uniqueEmail()).accessToken();
        UUID taskId = createTask(owner, "{\"content\": \"của tôi\"}");
        UUID projectId = createProject(owner, "riêng");
        UUID labelId = idOf(apiPost(owner, "/api/v1/labels", "{\"name\": \"mine\"}"));

        String body = syncOk(other, request("*",
                cmd("task_update", "{\"id\": \"" + taskId + "\", \"content\": \"hack\"}"),
                cmd("task_delete", "{\"id\": \"" + taskId + "\"}"),
                cmd("project_delete", "{\"id\": \"" + projectId + "\"}"),
                cmd("task_add", "{\"content\": \"x\", \"projectId\": \"" + projectId + "\"}"),
                cmd("label_delete", "{\"id\": \"" + labelId + "\"}"),
                cmd("task_add", "{\"id\": \"" + taskId + "\", \"content\": \"trùng id\"}")));

        assertThat(okStatuses(body)).isEqualTo("error,error,error,error,error,error");
        List<Integer> statuses = JsonPath.read(body, "$.results[*].error.status");
        assertThat(statuses).containsExactly(404, 404, 404, 404, 404, 409);
        assertThat((String) JsonPath.read(body, "$.results[5].error.code")).isEqualTo("id-conflict");
        apiGet(owner, "/api/v1/tasks/" + taskId).andExpect(jsonPath("$.content").value("của tôi"));
        // người lạ không thấy dữ liệu của chủ qua changes
        assertThat(idsOf(body, "tasks")).doesNotContain(taskId.toString());
        assertThat(idsOf(body, "projects")).doesNotContain(projectId.toString());
    }

    @Test
    void usageLimits_applyLikeRestApi() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        usageLimits.overrideMax(LimitKind.LABELS, 1);

        String body = syncOk(token, request("*",
                cmd("label_add", "{\"name\": \"one\"}"),
                cmd("label_add", "{\"name\": \"two\"}")));

        assertThat(okStatuses(body)).isEqualTo("ok,error");
        assertThat((Integer) JsonPath.read(body, "$.results[1].error.status")).isEqualTo(422);
        assertThat((String) JsonPath.read(body, "$.results[1].error.code")).isEqualTo("limit-reached");
        assertThat((String) JsonPath.read(body, "$.results[1].error.limit")).isEqualTo("LABELS");
    }

    @Test
    void addWithExistingIdAndSameContent_isIdempotentReplay() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        UUID taskId = UUID.randomUUID();
        // hai lệnh KHÁC uuid, cùng id + cùng nội dung → replay theo id (spec 12.11), không tạo trùng
        String body = syncOk(token, request("*",
                cmd("task_add", "{\"id\": \"" + taskId + "\", \"content\": \"A\"}"),
                cmd("task_add", "{\"id\": \"" + taskId + "\", \"content\": \"A\"}")));
        assertThat(okStatuses(body)).isEqualTo("ok,ok");
        assertThat(count("select count(*) from tasks where id = ?", taskId)).isOne();
    }

    @Test
    void moreThan100Commands_isRejected400() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        List<String> commands = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            commands.add(cmd("label_delete", "{\"id\": \"" + UUID.randomUUID() + "\"}"));
        }
        sync(token, request("*", commands.toArray(String[]::new))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("commands")));
        assertThat(okStatuses(syncOk(token, request("*", commands.subList(0, 100).toArray(String[]::new)))))
                .doesNotContain("ok"); // đúng 100 lệnh được nhận (đều 404)
    }

    @Test
    void malformedRequests_are400() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        sync(token, "{\"commands\": [{\"type\": \"task_add\"}]}").andExpect(status().isBadRequest()); // thiếu uuid
        sync(token, "{\"commands\": [{\"uuid\": \"x\", \"type\": \"task_add\"}]}").andExpect(status().isBadRequest());
        sync(token, "{\"syncToken\": \"abc\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("sync-token-invalid"));
        sync(token, "{\"resourceTypes\": [\"nope\"]}").andExpect(status().isBadRequest());
        String body = syncOk(token, request("*", cmd(UUID.randomUUID(), "task_add", null, "[1]")));
        assertThat(okStatuses(body)).isEqualTo("error");
    }

    @Test
    void invalidArgs_reportValidationErrorsWithRestKeys() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        String body = syncOk(token, request("*",
                cmd("task_add", "{\"content\": \"ok\", \"priority\": 9}"),
                cmd("task_update", "{\"content\": \"không có id\"}"),
                cmd("task_add", "{\"content\": \"ok\", \"projectId\": \"không-phải-uuid\"}")));
        assertThat(okStatuses(body)).isEqualTo("error,error,error");
        assertThat((String) JsonPath.read(body, "$.results[0].error.errors[0].field")).isEqualTo("priority");
        assertThat((Integer) JsonPath.read(body, "$.results[1].error.status")).isEqualTo(400);
        assertThat((Integer) JsonPath.read(body, "$.results[2].error.status")).isEqualTo(400);
    }
}
