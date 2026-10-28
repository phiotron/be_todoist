package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.support.TestData.TaskRow;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * M6c việc lặp (spec 3.2, 5.3; contract 8.1): lưu rule qua POST / PATCH tasks, close nhảy kỳ (không đặt completedAt),
 * Complete forever, Reset sub-tasks, Undo trả due cũ, deadline giữ, tasks/batch, filter recurring, Idempotency-Key.
 * "Bây giờ" = 2026-10-27T03:00Z = Thứ Ba 27/10 10:00 theo múi giờ tài khoản mặc định Asia/Ho_Chi_Minh.
 */
class TaskRecurrenceApiTest extends IntegrationTestBase {

    private static final String TASKS = "/api/v1/tasks";
    private static final Instant NOW = Instant.parse("2026-10-27T03:00:00Z");
    private static final Instant DONE_EARLIER = Instant.parse("2026-10-20T03:00:00Z");

    private RegisteredUser user;
    private UUID project;

    @BeforeEach
    void setUp() throws Exception {
        clock.freezeAt(NOW);
        user = registerUser(uniqueEmail());
        project = testData.project(user.id(), "P");
    }

    private UUID create(String json) throws Exception {
        return readUuid(authPost(user.accessToken(), TASKS, json).andExpect(status().isCreated()), "$.id");
    }

    private UUID recurring(String rule, String due) throws Exception {
        String dueField = due.contains("T") ? "dueDatetime" : "dueDate";
        return create("""
                {"projectId": "%s", "content": "Lặp", "%s": "%s", "recurrenceRule": "%s"}
                """.formatted(project, dueField, due, rule));
    }

    private String close(UUID id) {
        return TASKS + "/" + id + "/close";
    }

    private String reopen(UUID id) {
        return TASKS + "/" + id + "/reopen";
    }

    private Instant completedAt(UUID id) {
        Timestamp value = jdbcTemplate.queryForObject("select completed_at from tasks where id = ?", Timestamp.class, id);
        return value == null ? null : value.toInstant();
    }

    private LocalDate dueDate(UUID id) {
        return jdbcTemplate.queryForObject("select due_date from tasks where id = ?", LocalDate.class, id);
    }

    // ---- tạo / sửa ----

    @Test
    void create_storesCanonicalAnchoredRule_andReturnsRecurrenceFields() throws Exception {
        authPost(user.accessToken(), TASKS, """
                {"projectId": "%s", "content": "Báo cáo", "dueDate": "2026-10-31", "recurrenceRule": "FREQ=MONTHLY",
                 "dueString": "every month", "deadlineDate": "2026-11-05"}
                """.formatted(project))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recurrenceRule").value("FREQ=MONTHLY;BYMONTHDAY=31"))
                .andExpect(jsonPath("$.recurrenceFromCompletion").value(false))
                .andExpect(jsonPath("$.isRecurring").value(true))
                .andExpect(jsonPath("$.dueString").value("every month"))
                .andExpect(jsonPath("$.dueTimezone").value(nullValue()))
                .andExpect(jsonPath("$.deadlineDate").value("2026-11-05")); // task lặp ĐƯỢC có deadline (bản 3.2)
    }

    @Test
    void create_everyBang_keepsTheRuleUnanchored() throws Exception {
        authPost(user.accessToken(), TASKS, """
                {"projectId": "%s", "content": "Tưới cây", "dueDate": "2026-10-27", "recurrenceRule": "FREQ=DAILY;INTERVAL=3",
                 "recurrenceFromCompletion": true}
                """.formatted(project))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recurrenceRule").value("FREQ=DAILY;INTERVAL=3"))
                .andExpect(jsonPath("$.recurrenceFromCompletion").value(true));
    }

    @Test
    void create_plainTask_isNotRecurring() throws Exception {
        authPost(user.accessToken(), TASKS, "{\"content\": \"x\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recurrenceRule").value(nullValue()))
                .andExpect(jsonPath("$.recurrenceFromCompletion").value(false))
                .andExpect(jsonPath("$.isRecurring").value(false));
    }

    @Test
    void create_invalidRule_or_ruleWithoutDue_returns400WithCode() throws Exception {
        authPost(user.accessToken(), TASKS, """
                {"content": "x", "dueDate": "2026-10-27", "recurrenceRule": "FREQ=DAILY;COUNT=3"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("recurrence-invalid"))
                .andExpect(jsonPath("$.detail").value("Quy tắc lặp không được hỗ trợ"));
        authPost(user.accessToken(), TASKS, """
                {"content": "x", "recurrenceRule": "FREQ=DAILY"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("recurrence-requires-due"));
        authPost(user.accessToken(), TASKS, "{\"content\": \"x\", \"dueString\": \"%s\"}".formatted("a".repeat(151)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("dueString"));
        assertThat(jdbcTemplate.queryForObject("select count(*) from tasks where creator_id = ?", Long.class, user.id()))
                .isZero();
    }

    @Test
    void patch_setsChangesAndClearsTheRule() throws Exception {
        UUID id = create("""
                {"projectId": "%s", "content": "x", "dueDate": "2026-10-28"}
                """.formatted(project));
        authPatch(user.accessToken(), TASKS + "/" + id, """
                {"version": 0, "recurrenceRule": "FREQ=WEEKLY;INTERVAL=2"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recurrenceRule").value("FREQ=WEEKLY;INTERVAL=2;BYDAY=WE"))
                .andExpect(jsonPath("$.isRecurring").value(true))
                .andExpect(jsonPath("$.version").value(1));
        authPatch(user.accessToken(), TASKS + "/" + id, """
                {"version": 1, "recurrenceFromCompletion": true}
                """)
                .andExpect(jsonPath("$.recurrenceFromCompletion").value(true))
                .andExpect(jsonPath("$.recurrenceRule").value("FREQ=WEEKLY;INTERVAL=2;BYDAY=WE"));
        authPatch(user.accessToken(), TASKS + "/" + id, """
                {"version": 2, "recurrenceRule": null}
                """)
                .andExpect(jsonPath("$.recurrenceRule").value(nullValue()))
                .andExpect(jsonPath("$.recurrenceFromCompletion").value(false))
                .andExpect(jsonPath("$.isRecurring").value(false));
    }

    @Test
    void patch_removingTheDue_dropsTheRule_butAnExplicitRuleWithoutDueIs400() throws Exception {
        UUID id = recurring("FREQ=DAILY", "2026-10-27");
        authPatch(user.accessToken(), TASKS + "/" + id, """
                {"version": 0, "dueDate": null, "recurrenceRule": "FREQ=DAILY"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("recurrence-requires-due"));
        authPatch(user.accessToken(), TASKS + "/" + id, """
                {"version": 0, "recurrenceRule": "RRULE:FREQ=DAILY"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("recurrence-invalid"));
        authPatch(user.accessToken(), TASKS + "/" + id, """
                {"version": 0, "dueDate": null}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dueDate").value(nullValue()))
                .andExpect(jsonPath("$.isRecurring").value(false));
    }

    @Test
    void patch_changingTheDueWithoutDueString_clearsTheOldDueString() throws Exception {
        UUID id = create("""
                {"projectId": "%s", "content": "x", "dueDate": "2026-10-28", "dueString": "tomorrow"}
                """.formatted(project));
        authPatch(user.accessToken(), TASKS + "/" + id, "{\"version\": 0, \"priority\": 1}")
                .andExpect(jsonPath("$.dueString").value("tomorrow"));
        authPatch(user.accessToken(), TASKS + "/" + id, "{\"version\": 1, \"dueDate\": \"2026-10-30\"}")
                .andExpect(jsonPath("$.dueString").value(nullValue()));
        authPatch(user.accessToken(), TASKS + "/" + id, """
                {"version": 2, "dueDate": "2026-11-02", "dueString": "every monday", "recurrenceRule": "FREQ=WEEKLY;BYDAY=MO"}
                """)
                .andExpect(jsonPath("$.dueString").value("every monday"))
                .andExpect(jsonPath("$.recurrenceRule").value("FREQ=WEEKLY;BYDAY=MO"));
    }

    @Test
    void dueTimezone_onlyWithATime_andOnlyTheAccountZone() throws Exception {
        UUID id = create("""
                {"projectId": "%s", "content": "Họp", "dueDatetime": "2026-10-28T09:00", "dueTimezone": "Asia/Ho_Chi_Minh"}
                """.formatted(project));
        authGet(user.accessToken(), TASKS + "/" + id).andExpect(jsonPath("$.dueTimezone").value("Asia/Ho_Chi_Minh"));
        authPost(user.accessToken(), TASKS, """
                {"content": "x", "dueDatetime": "2026-10-28T09:00", "dueTimezone": "Europe/Paris"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "Múi giờ cố định chỉ dùng cho công việc có giờ và phải là múi giờ hiện tại của bạn"));
        authPost(user.accessToken(), TASKS, """
                {"content": "x", "dueDate": "2026-10-28", "dueTimezone": "Asia/Ho_Chi_Minh"}
                """)
                .andExpect(status().isBadRequest());
        // mất giờ → về floating
        authPatch(user.accessToken(), TASKS + "/" + id, "{\"version\": 0, \"dueDate\": \"2026-10-28\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dueTimezone").value(nullValue()));
    }

    // ---- hoàn thành ----

    @Test
    void close_recurring_movesTheDue_keepsItOpen_keepsDeadline_bumpsVersion() throws Exception {
        UUID id = create("""
                {"projectId": "%s", "content": "Báo cáo", "dueDate": "2026-10-27", "recurrenceRule": "FREQ=WEEKLY;BYDAY=TU",
                 "deadlineDate": "2026-12-01"}
                """.formatted(project));

        authPost(user.accessToken(), close(id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.completedAt").value(nullValue()))
                .andExpect(jsonPath("$.task.dueDate").value("2026-11-03"))
                .andExpect(jsonPath("$.task.deadlineDate").value("2026-12-01"))
                .andExpect(jsonPath("$.task.isRecurring").value(true))
                .andExpect(jsonPath("$.task.version").value(1))
                .andExpect(jsonPath("$.cascaded", empty()));

        assertThat(completedAt(id)).isNull();
        assertThat(testData.taskCompletionBatch(id)).isNull();
    }

    @Test
    void close_timedRecurring_keepsTimeAndDuration() throws Exception {
        UUID id = create("""
                {"projectId": "%s", "content": "Chạy bộ", "dueDatetime": "2026-10-27T06:30", "durationMinutes": 45,
                 "recurrenceRule": "FREQ=DAILY"}
                """.formatted(project));
        authPost(user.accessToken(), close(id))
                .andExpect(jsonPath("$.task.dueDatetime").value("2026-10-28T06:30:00"))
                .andExpect(jsonPath("$.task.durationMinutes").value(45));
    }

    @Test
    void close_overdue_jumpsToTheFirstOccurrenceAfterToday() throws Exception {
        UUID id = recurring("FREQ=DAILY", "2026-10-20");
        authPost(user.accessToken(), close(id)).andExpect(jsonPath("$.task.dueDate").value("2026-10-28"));
    }

    @Test
    void close_monthEnd_31_30_31() throws Exception {
        UUID id = recurring("FREQ=MONTHLY", "2026-03-31");
        clock.freezeAt(Instant.parse("2026-03-31T03:00:00Z"));
        authPost(user.accessToken(), close(id)).andExpect(jsonPath("$.task.dueDate").value("2026-04-30"));
        clock.freezeAt(Instant.parse("2026-04-30T03:00:00Z"));
        authPost(user.accessToken(), close(id)).andExpect(jsonPath("$.task.dueDate").value("2026-05-31"));
    }

    @Test
    void close_everyBang_countsFromTheCompletionDay() throws Exception {
        UUID id = create("""
                {"projectId": "%s", "content": "x", "dueDate": "2026-10-20", "recurrenceRule": "FREQ=DAILY;INTERVAL=3",
                 "recurrenceFromCompletion": true}
                """.formatted(project));
        authPost(user.accessToken(), close(id)).andExpect(jsonPath("$.task.dueDate").value("2026-10-30"));
    }

    @Test
    void close_lastOccurrenceBeforeUntil_completesForReal() throws Exception {
        UUID id = recurring("FREQ=DAILY;UNTIL=20261027", "2026-10-27");
        authPost(user.accessToken(), close(id))
                .andExpect(jsonPath("$.task.completedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.task.dueDate").value("2026-10-27"));
    }

    @Test
    void close_forever_completesTheTaskAndItsOpenSubtasks() throws Exception {
        UUID id = recurring("FREQ=DAILY", "2026-10-27");
        UUID child = testData.insert(TaskRow.of(user.id(), project, "con").parent(id));
        authPost(user.accessToken(), close(id), "{\"forever\": true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.completedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.task.dueDate").value("2026-10-27"))
                .andExpect(jsonPath("$.cascaded[*].id", contains(child.toString())));
        assertThat(completedAt(child)).isEqualTo(NOW);
        // Undo của Complete forever = Undo thường (mở lại cả con cùng batch)
        authPost(user.accessToken(), reopen(id), "{\"undo\": true}")
                .andExpect(jsonPath("$.task.completedAt").value(nullValue()));
        assertThat(completedAt(child)).isNull();
    }

    @Test
    void closeForever_afterAnAdvance_dropsTheOldUndoSnapshot() throws Exception {
        UUID id = recurring("FREQ=DAILY", "2026-10-27");
        authPost(user.accessToken(), close(id)).andExpect(jsonPath("$.task.dueDate").value("2026-10-28"));
        authPost(user.accessToken(), close(id), "{\"forever\": true}")
                .andExpect(jsonPath("$.task.completedAt").value(NOW.toString()));
        assertThat(jdbcTemplate.queryForObject("select recurrence_undo_batch_id from tasks where id = ?", UUID.class, id))
                .isNull();

        // Undo = mở lại task, KHÔNG trả due về ngày cũ của lần nhảy kỳ trước
        authPost(user.accessToken(), reopen(id), "{\"undo\": true}")
                .andExpect(jsonPath("$.task.completedAt").value(nullValue()))
                .andExpect(jsonPath("$.task.dueDate").value("2026-10-28"));
        // và một Undo thừa sau đó không đổi gì
        authPost(user.accessToken(), reopen(id), "{\"undo\": true}")
                .andExpect(jsonPath("$.task.dueDate").value("2026-10-28"));
    }

    @Test
    void close_recurring_doesNotCascadeToSubtasks() throws Exception {
        UUID id = recurring("FREQ=DAILY", "2026-10-27");
        UUID child = testData.insert(TaskRow.of(user.id(), project, "con").parent(id));
        authPost(user.accessToken(), close(id)).andExpect(jsonPath("$.cascaded", empty()));
        assertThat(completedAt(child)).isNull();
    }

    @Test
    void close_resetSubtasks_reopensCompletedDirectChildrenOnly() throws Exception {
        UUID id = recurring("FREQ=DAILY", "2026-10-27");
        UUID done = testData.insert(TaskRow.of(user.id(), project, "xong").parent(id).completedAt(DONE_EARLIER));
        UUID open = testData.insert(TaskRow.of(user.id(), project, "mở").parent(id));
        UUID deepDone = testData.insert(TaskRow.of(user.id(), project, "cấp 3").parent(done).completedAt(DONE_EARLIER));

        authPost(user.accessToken(), close(id), "{\"resetSubtasks\": true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.dueDate").value("2026-10-28"))
                .andExpect(jsonPath("$.task.completedSubtaskCount").value(0))
                .andExpect(jsonPath("$.cascaded[*].id", contains(done.toString())))
                .andExpect(jsonPath("$.cascaded[0].completedAt").value(nullValue()))
                .andExpect(jsonPath("$.cascaded[0].version").value(1));

        assertThat(completedAt(done)).isNull();
        assertThat(completedAt(open)).isNull();
        assertThat(completedAt(deepDone)).as("cấp 3 trở xuống không reset").isEqualTo(DONE_EARLIER);
    }

    @Test
    void close_resetSubtasks_atMost30Children() throws Exception {
        UUID id = recurring("FREQ=DAILY", "2026-10-27");
        for (int i = 0; i < 32; i++) {
            testData.insert(TaskRow.of(user.id(), project, "c" + i).parent(id).completedAt(DONE_EARLIER));
        }
        authPost(user.accessToken(), close(id), "{\"resetSubtasks\": true}")
                .andExpect(jsonPath("$.cascaded.length()").value(30))
                .andExpect(jsonPath("$.task.completedSubtaskCount").value(2));
    }

    @Test
    void close_followsTheResetSubtasksSetting_unlessTheBodySaysOtherwise() throws Exception {
        authPatch(user.accessToken(), "/api/v1/me/settings", "{\"resetSubtasks\": true}").andExpect(status().isOk());
        UUID id = recurring("FREQ=DAILY", "2026-10-27");
        UUID done = testData.insert(TaskRow.of(user.id(), project, "xong").parent(id).completedAt(DONE_EARLIER));

        authPost(user.accessToken(), close(id), "{\"resetSubtasks\": false}").andExpect(jsonPath("$.cascaded", empty()));
        assertThat(completedAt(done)).isEqualTo(DONE_EARLIER);
        authPost(user.accessToken(), close(id)).andExpect(jsonPath("$.cascaded[*].id", contains(done.toString())));
        assertThat(completedAt(done)).isNull();
    }

    @Test
    void close_resetThatWouldPassTheOpenTaskLimit_is422_andChangesNothing() throws Exception {
        UUID id = recurring("FREQ=DAILY", "2026-10-27");
        testData.insert(TaskRow.of(user.id(), project, "xong").parent(id).completedAt(DONE_EARLIER));
        usageLimits.overrideMax(com.taskly.entity.LimitKind.PROJECT_ACTIVE_TASKS, 1);
        authPost(user.accessToken(), close(id), "{\"resetSubtasks\": true}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("limit-reached"));
        assertThat(dueDate(id)).isEqualTo(LocalDate.of(2026, 10, 27));
    }

    // ---- Undo ----

    @Test
    void undo_restoresThePreviousDue_andRecompletesTheResetChildren() throws Exception {
        UUID id = recurring("FREQ=DAILY", "2026-10-27");
        UUID done = testData.insert(TaskRow.of(user.id(), project, "xong").parent(id).completedAt(DONE_EARLIER));
        authPost(user.accessToken(), close(id), "{\"resetSubtasks\": true}").andExpect(status().isOk());

        authPost(user.accessToken(), reopen(id), "{\"undo\": true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.dueDate").value("2026-10-27"))
                .andExpect(jsonPath("$.task.completedAt").value(nullValue()))
                .andExpect(jsonPath("$.task.version").value(2))
                .andExpect(jsonPath("$.cascaded[*].id", contains(done.toString())))
                .andExpect(jsonPath("$.cascaded[0].completedAt").value(DONE_EARLIER.toString()));
        assertThat(completedAt(done)).isEqualTo(DONE_EARLIER);

        // Undo lần 2: không còn ảnh chụp → không đổi gì
        authPost(user.accessToken(), reopen(id), "{\"undo\": true}")
                .andExpect(jsonPath("$.task.version").value(2))
                .andExpect(jsonPath("$.task.dueDate").value("2026-10-27"));
    }

    @Test
    void plainReopen_ofAnOpenRecurringTask_changesNothing() throws Exception {
        UUID id = recurring("FREQ=DAILY", "2026-10-27");
        authPost(user.accessToken(), close(id)).andExpect(status().isOk());
        authPost(user.accessToken(), reopen(id))
                .andExpect(jsonPath("$.task.dueDate").value("2026-10-28"))
                .andExpect(jsonPath("$.task.version").value(1));
    }

    @Test
    void undo_afterTheDueWasEditedByHand_doesNothing() throws Exception {
        UUID id = recurring("FREQ=DAILY", "2026-10-27");
        authPost(user.accessToken(), close(id)).andExpect(status().isOk());
        authPatch(user.accessToken(), TASKS + "/" + id, "{\"version\": 1, \"dueDate\": \"2026-11-15\"}")
                .andExpect(status().isOk());
        authPost(user.accessToken(), reopen(id), "{\"undo\": true}")
                .andExpect(jsonPath("$.task.dueDate").value("2026-11-15"));
    }

    // ---- cài đặt Reset sub-tasks ----

    @Test
    void settings_resetSubtasks_defaultsOff_canBeSaved_andResetTurnsItOff() throws Exception {
        authGet(user.accessToken(), "/api/v1/me/settings").andExpect(jsonPath("$.resetSubtasks").value(false));
        authPatch(user.accessToken(), "/api/v1/me/settings", "{\"resetSubtasks\": true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resetSubtasks").value(true));
        authGet(user.accessToken(), "/api/v1/me/settings").andExpect(jsonPath("$.resetSubtasks").value(true));
        authPost(user.accessToken(), "/api/v1/me/settings/reset")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resetSubtasks").value(false));
    }

    // ---- chọn nhiều, filter, sao chép, Idempotency-Key ----

    @Test
    void batchClose_movesRecurringTasks_completesOthers_andBatchUndoRestores() throws Exception {
        UUID rec = recurring("FREQ=WEEKLY;BYDAY=TU", "2026-10-27");
        UUID plain = create("{\"projectId\": \"%s\", \"content\": \"thường\"}".formatted(project));

        authPost(user.accessToken(), TASKS + "/batch",
                "{\"action\": \"CLOSE\", \"taskIds\": [\"%s\", \"%s\"]}".formatted(rec, plain))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedTaskIds", containsInAnyOrder(rec.toString(), plain.toString())));
        assertThat(dueDate(rec)).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(completedAt(rec)).isNull();
        assertThat(completedAt(plain)).isEqualTo(NOW);

        authPost(user.accessToken(), TASKS + "/batch",
                "{\"action\": \"REOPEN\", \"undo\": true, \"taskIds\": [\"%s\", \"%s\"]}".formatted(rec, plain))
                .andExpect(status().isOk());
        assertThat(dueDate(rec)).isEqualTo(LocalDate.of(2026, 10, 27));
        assertThat(completedAt(plain)).isNull();
    }

    @Test
    void filterRecurring_matchesRecurringTasksOnly() throws Exception {
        UUID rec = recurring("FREQ=DAILY", "2026-10-27");
        create("{\"projectId\": \"%s\", \"content\": \"thường\", \"dueDate\": \"2026-10-27\"}".formatted(project));
        authGet(user.accessToken(), TASKS + "?filter=recurring")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", contains(rec.toString())))
                .andExpect(jsonPath("$.items[0].isRecurring").value(true));
        authGet(user.accessToken(), TASKS + "?filter=!recurring")
                .andExpect(jsonPath("$.items[*].isRecurring", contains(false)));
    }

    @Test
    void duplicate_copiesTheRecurrence() throws Exception {
        UUID rec = create("""
                {"projectId": "%s", "content": "x", "dueDate": "2026-10-27", "recurrenceRule": "FREQ=DAILY;INTERVAL=2",
                 "recurrenceFromCompletion": true}
                """.formatted(project));
        mockMvc.perform(post(TASKS + "/" + rec + "/duplicate").header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recurrenceRule").value("FREQ=DAILY;INTERVAL=2"))
                .andExpect(jsonPath("$.recurrenceFromCompletion").value(true));
    }

    @Test
    void close_withTheSameIdempotencyKey_doesNotSkipASecondOccurrence() throws Exception {
        UUID id = recurring("FREQ=DAILY", "2026-10-27");
        String key = UUID.randomUUID().toString();
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post(close(id)).header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.task.dueDate").value("2026-10-28"));
        }
        mockMvc.perform(post(close(id)).header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(header().string("Idempotent-Replay", "true"));
        assertThat(dueDate(id)).isEqualTo(LocalDate.of(2026, 10, 28));
    }
}
