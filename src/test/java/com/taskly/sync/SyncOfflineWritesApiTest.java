package com.taskly.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.entity.LimitKind;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * POST /api/v1/sync — các lệnh GHI offline thêm ở giai đoạn 2 của M9 (plan 8.3): reorder, task_batch,
 * task_reschedule_overdue, *_favorite / *_unfavorite, reminder_add / reminder_delete. Mỗi lệnh gọi lại service
 * có sẵn nên quyền / giới hạn / lỗi như REST; ở đây kiểm: chạy được, lỗi nằm trong results, không chạy lại khi cùng uuid.
 */
class SyncOfflineWritesApiTest extends SyncTestBase {

    private static final Instant NOW = Instant.parse("2026-10-23T03:00:00Z"); // 10:00 ngày 23 giờ Việt Nam

    private String token;

    @BeforeEach
    void user() throws Exception {
        clock.freezeAt(NOW);
        token = registerUser(uniqueEmail()).accessToken();
    }

    private static String q(UUID id) {
        return "\"" + id + "\"";
    }

    private static String ids(UUID... ids) {
        return java.util.Arrays.stream(ids).map(SyncOfflineWritesApiTest::q)
                .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
    }

    private long sortOrder(String table, UUID id) {
        return jdbcTemplate.queryForObject("select sort_order from " + table + " where id = ?", Long.class, id);
    }

    private String taskField(UUID id, String column) {
        return jdbcTemplate.queryForObject("select cast(" + column + " as text) from tasks where id = ?", String.class, id);
    }

    private UUID createLabel(String name) throws Exception {
        return idOf(apiPost(token, "/api/v1/labels", "{\"name\": \"" + name + "\"}"));
    }

    private UUID createFilter(String name) throws Exception {
        return idOf(apiPost(token, "/api/v1/filters", "{\"name\": \"" + name + "\", \"query\": \"p1\"}"));
    }

    // ---------------------------------------------------------------- reorder

    @Test
    void reorder_movesTaskSectionProjectLabelAndFilter() throws Exception {
        UUID projectId = createProject(token, "P");
        UUID otherProject = createProject(token, "Q");
        UUID t1 = createTask(token, "{\"content\": \"1\", \"projectId\": " + q(projectId) + "}");
        UUID t2 = createTask(token, "{\"content\": \"2\", \"projectId\": " + q(projectId) + "}");
        UUID t3 = createTask(token, "{\"content\": \"3\", \"projectId\": " + q(projectId) + "}");
        UUID s1 = createSection(token, projectId, "S1");
        UUID s2 = createSection(token, projectId, "S2");
        UUID l1 = createLabel("a");
        UUID l2 = createLabel("b");
        UUID f1 = createFilter("F1");
        UUID f2 = createFilter("F2");

        String body = syncOk(token, request("*",
                cmd("reorder", "{\"type\": \"TASK\", \"id\": " + q(t3) + ", \"afterId\": null}"),
                cmd("reorder", "{\"type\": \"TASK\", \"id\": " + q(t1) + ", \"afterId\": " + q(t2) + "}"),
                cmd("reorder", "{\"type\": \"SECTION\", \"id\": " + q(s2) + ", \"afterId\": null}"),
                cmd("reorder", "{\"type\": \"PROJECT\", \"id\": " + q(otherProject) + ", \"afterId\": null}"),
                cmd("reorder", "{\"type\": \"LABEL\", \"id\": " + q(l2) + ", \"afterId\": null}"),
                cmd("reorder", "{\"type\": \"FILTER\", \"id\": " + q(f2) + ", \"afterId\": null}")));

        assertThat(okStatuses(body)).isEqualTo("ok,ok,ok,ok,ok,ok");
        // t3 lên đầu, t1 ngay sau t2 → thứ tự t3, t2, t1
        assertThat(sortOrder("tasks", t3)).isLessThan(sortOrder("tasks", t2));
        assertThat(sortOrder("tasks", t2)).isLessThan(sortOrder("tasks", t1));
        assertThat(sortOrder("sections", s2)).isLessThan(sortOrder("sections", s1));
        assertThat(sortOrder("labels", l2)).isLessThan(sortOrder("labels", l1));
        assertThat(sortOrder("filters", f2)).isLessThan(sortOrder("filters", f1));
        List<String> projectIds = JsonPath.read(getOk(token, "/api/v1/projects"), "$[*].id");
        assertThat(projectIds.indexOf(otherProject.toString())).isLessThan(projectIds.indexOf(projectId.toString()));
    }

    @Test
    void reorder_errorsAreReportedPerCommand() throws Exception {
        String stranger = registerUser(uniqueEmail()).accessToken();
        UUID mine = createTask(token, "{\"content\": \"của tôi\"}");

        String body = syncOk(stranger, request("*",
                cmd("reorder", "{\"type\": \"TASK\", \"id\": " + q(mine) + ", \"afterId\": null}"), // của người khác
                cmd("reorder", "{\"type\": \"TASK\", \"id\": " + q(mine) + "}"), // thiếu afterId (bắt buộc có khoá)
                cmd("reorder", "{\"id\": " + q(mine) + ", \"afterId\": null}"), // thiếu type
                cmd("reorder", "{\"type\": \"TASK\", \"id\": " + q(UUID.randomUUID()) + ", \"afterId\": null}")));

        assertThat(okStatuses(body)).isEqualTo("error,error,error,error");
        List<Integer> statuses = JsonPath.read(body, "$.results[*].error.status");
        assertThat(statuses).containsExactly(404, 400, 400, 404);
        assertThat((String) JsonPath.read(body, "$.results[1].error.errors[0].field")).isEqualTo("afterId");
        assertThat((String) JsonPath.read(body, "$.results[2].error.errors[0].field")).isEqualTo("type");
    }

    @Test
    void reorder_afterIdOfARecordCreatedEarlierInTheSameBatch_usesTheTempId() throws Exception {
        UUID projectId = createProject(token, "P");
        UUID first = createTask(token, "{\"content\": \"có sẵn\", \"projectId\": " + q(projectId) + "}");
        UUID offline = UUID.randomUUID();

        String body = syncOk(token, request("*",
                cmd(UUID.randomUUID(), "task_add", "tmp-offline",
                        "{\"content\": \"offline\", \"projectId\": " + q(projectId) + "}"),
                cmd("reorder", "{\"type\": \"TASK\", \"id\": " + q(first) + ", \"afterId\": \"tmp-offline\"}")));

        assertThat(okStatuses(body)).isEqualTo("ok,ok");
        String created = JsonPath.read(body, "$.tempIdMapping['tmp-offline']");
        assertThat(sortOrder("tasks", first)).isGreaterThan(sortOrder("tasks", UUID.fromString(created)));
        assertThat(offline).isNotNull();
    }

    @Test
    void reorder_sameCommandUuid_runsOnce() throws Exception {
        UUID projectId = createProject(token, "P");
        UUID t1 = createTask(token, "{\"content\": \"1\", \"projectId\": " + q(projectId) + "}");
        UUID t2 = createTask(token, "{\"content\": \"2\", \"projectId\": " + q(projectId) + "}");
        UUID uuid = UUID.randomUUID();
        String move = cmd(uuid, "reorder", null, "{\"type\": \"TASK\", \"id\": " + q(t2) + ", \"afterId\": null}");

        syncOk(token, request("*", move));
        assertThat(sortOrder("tasks", t2)).isLessThan(sortOrder("tasks", t1));
        syncOk(token, request("*", cmd("reorder", "{\"type\": \"TASK\", \"id\": " + q(t1) + ", \"afterId\": null}")));
        assertThat(sortOrder("tasks", t1)).isLessThan(sortOrder("tasks", t2));

        String again = syncOk(token, request("*", move)); // gửi lại lệnh cũ: không chạy lại
        assertThat(okStatuses(again)).isEqualTo("ok");
        assertThat(sortOrder("tasks", t1)).isLessThan(sortOrder("tasks", t2));
    }

    // ---------------------------------------------------------------- task_batch

    @Test
    void taskBatch_closeReopenPriorityScheduleLabels() throws Exception {
        UUID a = createTask(token, "{\"content\": \"a\", \"dueDate\": \"2026-10-20\"}");
        UUID b = createTask(token, "{\"content\": \"b\"}");
        UUID label = createLabel("l");

        String body = syncOk(token, request("*",
                cmd("task_batch", "{\"action\": \"PRIORITY\", \"taskIds\": " + ids(a, b) + ", \"priority\": 1}"),
                cmd("task_batch", "{\"action\": \"SCHEDULE\", \"taskIds\": " + ids(a, b) + ", \"date\": \"2026-10-25\"}"),
                cmd("task_batch", "{\"action\": \"ADD_LABELS\", \"taskIds\": " + ids(a, b) + ", \"labelIds\": " + ids(label) + "}"),
                cmd("task_batch", "{\"action\": \"CLOSE\", \"taskIds\": " + ids(a) + "}")));

        assertThat(okStatuses(body)).isEqualTo("ok,ok,ok,ok");
        assertThat(taskField(a, "priority")).isEqualTo("1");
        assertThat(taskField(b, "due_date")).isEqualTo("2026-10-25");
        assertThat(taskField(a, "completed_at")).isNotNull();
        assertThat(taskField(b, "completed_at")).isNull();
        assertThat(count("select count(*) from task_labels where label_id = ?", label)).isEqualTo(2);

        String second = syncOk(token, request("*",
                cmd("task_batch", "{\"action\": \"REOPEN\", \"taskIds\": " + ids(a) + ", \"undo\": true}"),
                cmd("task_batch", "{\"action\": \"REMOVE_LABELS\", \"taskIds\": " + ids(a, b) + ", \"labelIds\": " + ids(label) + "}"),
                cmd("task_batch", "{\"action\": \"SCHEDULE\", \"taskIds\": " + ids(a) + ", \"date\": null}")));
        assertThat(okStatuses(second)).isEqualTo("ok,ok,ok");
        assertThat(taskField(a, "completed_at")).isNull();
        assertThat(taskField(a, "due_date")).isNull();
        assertThat(count("select count(*) from task_labels where label_id = ?", label)).isZero();
    }

    @Test
    void taskBatch_moveAndDelete_afterQueuedCreates() throws Exception {
        UUID target = createProject(token, "Đích");
        UUID existing = createTask(token, "{\"content\": \"có sẵn\"}");

        String body = syncOk(token, request("*",
                cmd(UUID.randomUUID(), "task_add", "tmp-new", "{\"content\": \"mới\"}"),
                cmd("task_batch", "{\"action\": \"MOVE\", \"taskIds\": [\"tmp-new\", " + q(existing) + "], \"projectId\": " + q(target) + "}")));

        assertThat(okStatuses(body)).isEqualTo("ok,ok");
        String created = JsonPath.read(body, "$.tempIdMapping['tmp-new']");
        assertThat(taskField(UUID.fromString(created), "project_id")).isEqualTo(target.toString());
        assertThat(taskField(existing, "project_id")).isEqualTo(target.toString());

        UUID deleteUuid = UUID.randomUUID();
        String delete = cmd(deleteUuid, "task_batch", null, "{\"action\": \"DELETE\", \"taskIds\": " + ids(existing) + "}");
        assertThat(okStatuses(syncOk(token, request("*", delete)))).isEqualTo("ok");
        assertThat(count("select count(*) from tasks where id = ?", existing)).isZero();
        // gửi lại cùng uuid: không chạy lại (chạy lại sẽ 404 vì task đã xoá)
        assertThat(okStatuses(syncOk(token, request("*", delete)))).isEqualTo("ok");
    }

    @Test
    void taskBatch_errorsAreReportedPerCommand_andRollBackWholeBatchCommand() throws Exception {
        String stranger = registerUser(uniqueEmail()).accessToken();
        UUID mine = createTask(token, "{\"content\": \"của tôi\"}");
        UUID strangers = createTask(stranger, "{\"content\": \"của người lạ\"}");

        String body = syncOk(token, request("*",
                cmd("task_batch", "{\"action\": \"PRIORITY\", \"taskIds\": " + ids(mine, strangers) + ", \"priority\": 1}"),
                cmd("task_batch", "{\"action\": \"PRIORITY\", \"taskIds\": " + ids(mine) + "}"), // thiếu priority
                cmd("task_batch", "{\"action\": \"BOGUS\", \"taskIds\": " + ids(mine) + "}"),
                cmd("task_batch", "{\"taskIds\": " + ids(mine) + "}"),
                cmd("task_batch", "{\"action\": \"CLOSE\", \"taskIds\": []}"),
                cmd("task_batch", "{\"action\": \"CLOSE\", \"taskIds\": " + ids(mine) + ", \"priority\": 2}"),
                cmd("task_batch", "{\"action\": \"CLOSE\", \"taskIds\": " + ids(mine) + "}")));

        assertThat(okStatuses(body)).isEqualTo("error,error,error,error,error,error,ok");
        List<Integer> statuses = JsonPath.read(body, "$.results[*].error.status");
        assertThat(statuses).containsExactly(404, 400, 400, 400, 400, 400);
        assertThat(taskField(mine, "priority")).isEqualTo("4"); // lệnh 404 không sửa task nào (cả lô rollback)
        assertThat(taskField(strangers, "priority")).isEqualTo("4");
        assertThat(taskField(mine, "completed_at")).isNotNull();
    }

    @Test
    void taskBatch_limitReopenKeepsRestLimits() throws Exception {
        UUID done = createTask(token, "{\"content\": \"xong\"}");
        syncOk(token, request("*", cmd("task_close", "{\"id\": " + q(done) + "}")));
        UUID open = createTask(token, "{\"content\": \"mở\"}");
        assertThat(open).isNotNull();
        usageLimits.overrideMax(LimitKind.PROJECT_ACTIVE_TASKS, 1);

        String body = syncOk(token, request("*",
                cmd("task_batch", "{\"action\": \"REOPEN\", \"taskIds\": " + ids(done) + "}")));

        assertThat(okStatuses(body)).isEqualTo("error");
        assertThat((Integer) JsonPath.read(body, "$.results[0].error.status")).isEqualTo(422);
        assertThat((String) JsonPath.read(body, "$.results[0].error.code")).isEqualTo("limit-reached");
    }

    // ---------------------------------------------------------------- task_reschedule_overdue

    @Test
    void rescheduleOverdue_movesOverdueTasks_andRejectsPastDate() throws Exception {
        UUID overdue = createTask(token, "{\"content\": \"quá hạn\", \"dueDate\": \"2026-10-20\"}");
        UUID future = createTask(token, "{\"content\": \"tương lai\", \"dueDate\": \"2026-11-01\"}");

        String body = syncOk(token, request("*",
                cmd("task_reschedule_overdue", "{\"date\": \"2026-10-22\"}"), // trước hôm nay (23) → 400
                cmd("task_reschedule_overdue", "{}"),
                cmd("task_reschedule_overdue", "{\"date\": \"2026-10-24\"}")));

        assertThat(okStatuses(body)).isEqualTo("error,error,ok");
        List<Integer> statuses = JsonPath.read(body, "$.results[*].error.status");
        assertThat(statuses.subList(0, 2)).containsExactly(400, 400);
        assertThat(taskField(overdue, "due_date")).isEqualTo("2026-10-24");
        assertThat(taskField(future, "due_date")).isEqualTo("2026-11-01");
    }

    // ---------------------------------------------------------------- favorites

    @Test
    void favoriteCommands_addAndRemoveForProjectLabelFilter() throws Exception {
        UUID projectId = createProject(token, "P");
        UUID labelId = createLabel("l");
        UUID filterId = createFilter("F");

        String body = syncOk(token, request("*",
                cmd("project_favorite", "{\"id\": " + q(projectId) + "}"),
                cmd("label_favorite", "{\"id\": " + q(labelId) + "}"),
                cmd("filter_favorite", "{\"id\": " + q(filterId) + "}")));

        assertThat(okStatuses(body)).isEqualTo("ok,ok,ok");
        List<String> targets = JsonPath.read(getOk(token, "/api/v1/favorites"), "$[*].targetId");
        assertThat(targets).containsExactlyInAnyOrder(projectId.toString(), labelId.toString(), filterId.toString());
        // sync báo bản ghi đổi (trigger sync_bump)
        assertThat(idsOf(body, "projects")).contains(projectId.toString());

        String removed = syncOk(token, request("*",
                cmd("project_unfavorite", "{\"id\": " + q(projectId) + "}"),
                cmd("label_unfavorite", "{\"id\": " + q(labelId) + "}"),
                cmd("filter_unfavorite", "{\"id\": " + q(filterId) + "}"),
                cmd("filter_unfavorite", "{\"id\": " + q(filterId) + "}"))); // bỏ lần nữa: vô hại như REST
        assertThat(okStatuses(removed)).isEqualTo("ok,ok,ok,ok");
        assertThat(JsonPath.<List<?>>read(getOk(token, "/api/v1/favorites"), "$")).isEmpty();
    }

    @Test
    void favoriteCommands_errors() throws Exception {
        String stranger = registerUser(uniqueEmail()).accessToken();
        UUID projectId = createProject(token, "P");
        UUID labelId = createLabel("l");
        UUID filterId = createFilter("F");
        String inbox = JsonPath.<List<String>>read(getOk(token, "/api/v1/projects"), "$[?(@.isInbox == true)].id").get(0);

        String body = syncOk(stranger, request("*",
                cmd("project_favorite", "{\"id\": " + q(projectId) + "}"),
                cmd("label_favorite", "{\"id\": " + q(labelId) + "}"),
                cmd("filter_unfavorite", "{\"id\": " + q(filterId) + "}"),
                cmd("project_favorite", "{}")));
        assertThat(okStatuses(body)).isEqualTo("error,error,error,error");
        List<Integer> statuses = JsonPath.read(body, "$.results[*].error.status");
        assertThat(statuses).containsExactly(404, 404, 404, 400);

        String own = syncOk(token, request("*", cmd("project_favorite", "{\"id\": \"" + inbox + "\"}")));
        assertThat(okStatuses(own)).isEqualTo("error"); // Inbox không yêu thích được (như REST)
    }

    // ---------------------------------------------------------------- reminders

    @Test
    void reminderAddAndDelete_work_andTempIdOfAReminderIsUsableByALaterCommand() throws Exception {
        UUID task = createTask(token, "{\"content\": \"họp\", \"dueDatetime\": \"2026-10-23T12:00:00\"}");
        String offlineId = UUID.randomUUID().toString();

        String body = syncOk(token, request("*",
                cmd(UUID.randomUUID(), "reminder_add", offlineId,
                        "{\"taskId\": " + q(task) + ", \"id\": \"" + offlineId + "\", \"type\": \"RELATIVE\", \"minuteOffset\": 30}"),
                cmd(UUID.randomUUID(), "reminder_add", null,
                        "{\"taskId\": " + q(task) + ", \"type\": \"ABSOLUTE\", \"at\": \"2026-10-23T11:30\"}"),
                cmd("reminder_delete", "{\"taskId\": " + q(task) + ", \"id\": \"" + offlineId + "\"}")));

        assertThat(okStatuses(body)).isEqualTo("ok,ok,ok");
        String realId = JsonPath.read(body, "$.tempIdMapping['" + offlineId + "']");
        assertThat(realId).isNotNull();
        List<String> types = JsonPath.read(getOk(token, "/api/v1/tasks/" + task + "/reminders"), "$[*].type");
        assertThat(types).containsExactly("ABSOLUTE"); // nhắc tương đối (id tạm) đã bị xoá bởi lệnh sau
        // sync lại cùng uuid của lệnh tạo: không tạo trùng
    }

    @Test
    void reminderAdd_sameUuidTwice_createsOne() throws Exception {
        UUID task = createTask(token, "{\"content\": \"họp\", \"dueDatetime\": \"2026-10-23T12:00:00\"}");
        String add = cmd(UUID.randomUUID(), "reminder_add", "tmp-r",
                "{\"taskId\": " + q(task) + ", \"type\": \"RELATIVE\", \"minuteOffset\": 15}");

        syncOk(token, request("*", add));
        String again = syncOk(token, request("*", add));

        assertThat(okStatuses(again)).isEqualTo("ok");
        assertThat(JsonPath.<List<?>>read(getOk(token, "/api/v1/tasks/" + task + "/reminders"), "$")).hasSize(1);
        assertThat((String) JsonPath.read(again, "$.tempIdMapping['tmp-r']")).isNotNull(); // mapping cũ được trả lại
    }

    @Test
    void reminderCommands_errors() throws Exception {
        String stranger = registerUser(uniqueEmail()).accessToken();
        UUID dateOnly = createTask(token, "{\"content\": \"chỉ ngày\", \"dueDate\": \"2026-10-24\"}");
        UUID timed = createTask(token, "{\"content\": \"có giờ\", \"dueDatetime\": \"2026-10-23T12:00:00\"}");
        usageLimits.overrideMax(LimitKind.REMINDERS, 1);

        String body = syncOk(token, request("*",
                cmd("reminder_add", "{\"taskId\": " + q(dateOnly) + ", \"type\": \"RELATIVE\", \"minuteOffset\": 30}"), // cần giờ
                cmd("reminder_add", "{\"taskId\": " + q(timed) + ", \"type\": \"ABSOLUTE\", \"at\": \"2026-10-22T09:00\"}"), // quá khứ
                cmd("reminder_add", "{\"type\": \"RELATIVE\", \"minuteOffset\": 30}"), // thiếu taskId
                cmd("reminder_add", "{\"taskId\": " + q(timed) + ", \"type\": \"RELATIVE\", \"minuteOffset\": 99999}"),
                cmd("reminder_add", "{\"taskId\": " + q(timed) + ", \"type\": \"RELATIVE\", \"minuteOffset\": 30}"),
                cmd("reminder_add", "{\"taskId\": " + q(timed) + ", \"type\": \"RELATIVE\", \"minuteOffset\": 45}"))); // vượt giới hạn

        assertThat(okStatuses(body)).isEqualTo("error,error,error,error,ok,error");
        List<Integer> statuses = JsonPath.read(body, "$.results[*].error.status");
        assertThat(statuses).containsExactly(400, 400, 400, 400, 422);
        assertThat((String) JsonPath.read(body, "$.results[5].error.code")).isEqualTo("limit-reached");

        String foreign = syncOk(stranger, request("*",
                cmd("reminder_add", "{\"taskId\": " + q(timed) + ", \"type\": \"RELATIVE\", \"minuteOffset\": 30}"),
                cmd("reminder_delete", "{\"taskId\": " + q(timed) + ", \"id\": " + q(UUID.randomUUID()) + "}")));
        assertThat(okStatuses(foreign)).isEqualTo("error,error");
        List<Integer> foreignStatuses = JsonPath.read(foreign, "$.results[*].error.status");
        assertThat(foreignStatuses).containsExactly(404, 404);
        apiGet(token, "/api/v1/tasks/" + timed + "/reminders").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }
}
