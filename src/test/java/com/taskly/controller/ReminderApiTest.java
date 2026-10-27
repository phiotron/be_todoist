package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.IntegrationTestBase;
import com.taskly.entity.LimitKind;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * GET / POST / DELETE /api/v1/tasks/{taskId}/reminders (spec 5.8, 12.8, 12.10, 12.11, 2.1). Giới hạn 700 được hạ
 * bằng usageLimits.overrideMax (IntegrationTestBase gỡ trước mỗi test) — không chèn 700 dòng.
 */
class ReminderApiTest extends IntegrationTestBase {

    /** 10:00 ngày 23/10/2026 ở Asia/Ho_Chi_Minh (múi giờ mặc định của tài khoản). */
    private static final Instant TEN_AM_HCM = Instant.parse("2026-10-23T03:00:00Z");
    private static final String RELATIVE_30 = """
            {"type": "RELATIVE", "minuteOffset": 30}
            """;
    private static final String ABSOLUTE_1130 = """
            {"type": "ABSOLUTE", "at": "2026-10-23T11:30"}
            """;
    private static final String FIELDS_VI =
            "Nhắc tuyệt đối cần at (không có minuteOffset), nhắc tương đối cần minuteOffset (không có at)";
    private static final String NEEDS_TIME_VI = "Nhắc tương đối chỉ dùng cho công việc có giờ";
    private static final String OFFSET_VI = "Nhắc trước phải từ 0 đến 10080 phút (7 ngày)";

    private static String reminders(UUID taskId) {
        return "/api/v1/tasks/" + taskId + "/reminders";
    }

    private RegisteredUser frozenUser() throws Exception {
        clock.freezeAt(TEN_AM_HCM);
        return registerUser(uniqueEmail());
    }

    private UUID createTask(RegisteredUser user, String json) throws Exception {
        return readUuid(authPost(user.accessToken(), "/api/v1/tasks", json).andExpect(status().isCreated()), "$.id");
    }

    private UUID timedTask(RegisteredUser user) throws Exception {
        return createTask(user, """
                {"content": "Họp nhóm", "dueDatetime": "2026-10-23T12:00:00"}
                """);
    }

    /** Chèn n nhắc tuyệt đối (giờ tương lai) vào task bằng SQL — dựng số đếm cho test giới hạn mà không gọi API n lần. */
    private void insertReminders(UUID taskId, UUID userId, int n) {
        Timestamp now = Timestamp.from(clock.instant());
        jdbcTemplate.update("""
                insert into reminders (id, task_id, user_id, type, at, minute_offset, created_at, updated_at)
                select gen_random_uuid(), ?, ?, 'ABSOLUTE', ?, null, ?, ?
                from generate_series(1, ?)
                """, taskId, userId, LocalDateTime.parse("2026-10-24T09:00:00"), now, now, n);
    }

    private UUID dateOnlyTask(RegisteredUser user) throws Exception {
        return createTask(user, """
                {"content": "Nộp báo cáo", "dueDate": "2026-10-24"}
                """);
    }

    private UUID taskWithoutDue(RegisteredUser user) throws Exception {
        return createTask(user, """
                {"content": "Đọc sách"}
                """);
    }

    private UUID project(RegisteredUser user, String name) throws Exception {
        return readUuid(authPost(user.accessToken(), "/api/v1/projects", """
                {"name": "%s"}
                """.formatted(name)).andExpect(status().isCreated()), "$.id");
    }

    private UUID childProject(RegisteredUser user, UUID parentId, String name) throws Exception {
        return readUuid(authPost(user.accessToken(), "/api/v1/projects", """
                {"name": "%s", "parentId": "%s"}
                """.formatted(name, parentId)).andExpect(status().isCreated()), "$.id");
    }

    private UUID section(RegisteredUser user, UUID projectId, String name) throws Exception {
        return readUuid(authPost(user.accessToken(), "/api/v1/projects/" + projectId + "/sections", """
                {"name": "%s"}
                """.formatted(name)).andExpect(status().isCreated()), "$.id");
    }

    private ResultActions postReminder(RegisteredUser user, UUID taskId, String json) throws Exception {
        return authPost(user.accessToken(), reminders(taskId), json);
    }

    private int reminderCount(UUID taskId) {
        return jdbcTemplate.queryForObject("select count(*) from reminders where task_id = ?", Integer.class, taskId);
    }

    // ---- tạo nhắc tuyệt đối ---------------------------------------------------------------------------

    @Test
    void createAbsolute_returns201AndIsListed() throws Exception {
        RegisteredUser user = frozenUser();
        UUID taskId = timedTask(user);

        String id = readString(postReminder(user, taskId, ABSOLUTE_1130)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.taskId").value(taskId.toString()))
                .andExpect(jsonPath("$.type").value("ABSOLUTE"))
                .andExpect(jsonPath("$.at").value("2026-10-23T11:30:00"))
                .andExpect(jsonPath("$.minuteOffset").value(nullValue()))
                .andExpect(jsonPath("$.createdAt").value("2026-10-23T03:00:00Z")), "$.id");

        authGet(user.accessToken(), reminders(taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id));
        assertThat(jdbcTemplate.queryForObject("select user_id from reminders where id = ?", UUID.class,
                UUID.fromString(id))).isEqualTo(user.id());
    }

    @Test
    void createAbsolute_dropsSeconds() throws Exception {
        RegisteredUser user = frozenUser();

        postReminder(user, timedTask(user), """
                {"type": "ABSOLUTE", "at": "2026-10-23T11:30:45"}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.at").value("2026-10-23T11:30:00"));
    }

    @Test
    void createAbsolute_onTaskWithoutDue_isAllowed() throws Exception {
        RegisteredUser user = frozenUser();

        postReminder(user, taskWithoutDue(user), ABSOLUTE_1130).andExpect(status().isCreated());
    }

    @Test
    void createAbsolute_inThePast_returns400() throws Exception {
        RegisteredUser user = frozenUser();

        postReminder(user, timedTask(user), """
                {"type": "ABSOLUTE", "at": "2026-10-23T09:59"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Thời điểm nhắc đã qua"));
    }

    @Test
    void createAbsolute_atExactlyNow_isAllowed() throws Exception {
        RegisteredUser user = frozenUser();

        postReminder(user, timedTask(user), """
                {"type": "ABSOLUTE", "at": "2026-10-23T10:00"}
                """).andExpect(status().isCreated());
    }

    @Test
    void createAbsolute_comparesWithNowInTheAccountTimeZone() throws Exception {
        RegisteredUser user = frozenUser(); // 03:00Z = 23:00 ngày 22/10 ở New York
        jdbcTemplate.update("update users set time_zone = 'America/New_York' where id = ?", user.id());
        UUID taskId = taskWithoutDue(user);

        postReminder(user, taskId, """
                {"type": "ABSOLUTE", "at": "2026-10-22T23:30"}
                """).andExpect(status().isCreated());
        postReminder(user, taskId, """
                {"type": "ABSOLUTE", "at": "2026-10-22T22:59"}
                """).andExpect(status().isBadRequest());
    }

    /** Giờ floating không có Z / offset (spec 5.2, cùng quy ước M1 Q32): Jackson từ chối → 400, không tạo nhắc. */
    @ParameterizedTest
    @ValueSource(strings = {"2026-10-23T11:30:00Z", "2026-10-23T11:30:00+07:00", "abc"})
    void createAbsolute_withOffsetOrGarbageAt_returns400(String at) throws Exception {
        RegisteredUser user = frozenUser();
        UUID taskId = timedTask(user);

        postReminder(user, taskId, """
                {"type": "ABSOLUTE", "at": "%s"}
                """.formatted(at))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        assertThat(reminderCount(taskId)).isZero();
    }

    // ---- tạo nhắc tương đối ---------------------------------------------------------------------------

    @Test
    void createRelative_forTimedTask_returns201() throws Exception {
        RegisteredUser user = frozenUser();

        postReminder(user, timedTask(user), RELATIVE_30)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("RELATIVE"))
                .andExpect(jsonPath("$.minuteOffset").value(30))
                .andExpect(jsonPath("$.at").value(nullValue()));
    }

    @Test
    void createRelative_forTaskWithoutTime_returns400() throws Exception {
        RegisteredUser user = frozenUser();

        postReminder(user, dateOnlyTask(user), RELATIVE_30)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(NEEDS_TIME_VI));
        postReminder(user, taskWithoutDue(user), RELATIVE_30)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(NEEDS_TIME_VI));
    }

    /** Không có unique: hai nhắc trùng hệt vẫn tạo được (lịch nhắc bỏ trùng ở GET tasks/reminders — Task 7). */
    @Test
    void identicalRemindersCanBeCreatedTwice() throws Exception {
        RegisteredUser user = frozenUser();
        UUID taskId = timedTask(user);

        postReminder(user, taskId, RELATIVE_30).andExpect(status().isCreated());
        postReminder(user, taskId, RELATIVE_30).andExpect(status().isCreated());
        postReminder(user, taskId, ABSOLUTE_1130).andExpect(status().isCreated());
        postReminder(user, taskId, ABSOLUTE_1130).andExpect(status().isCreated());

        authGet(user.accessToken(), reminders(taskId)).andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    void minuteOffsetAcceptsZeroAndSevenDays() throws Exception {
        RegisteredUser user = frozenUser();
        UUID taskId = timedTask(user);

        postReminder(user, taskId, """
                {"type": "RELATIVE", "minuteOffset": 0}
                """).andExpect(status().isCreated()).andExpect(jsonPath("$.minuteOffset").value(0));
        postReminder(user, taskId, """
                {"type": "RELATIVE", "minuteOffset": 10080}
                """).andExpect(status().isCreated()).andExpect(jsonPath("$.minuteOffset").value(10080));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 10081})
    void minuteOffsetOutsideZeroToSevenDays_returns400(int minutes) throws Exception {
        RegisteredUser user = frozenUser();

        postReminder(user, timedTask(user), """
                {"type": "RELATIVE", "minuteOffset": %d}
                """.formatted(minutes))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("minuteOffset"))
                .andExpect(jsonPath("$.errors[0].message").value(OFFSET_VI));
    }

    // ---- dữ liệu không khớp ---------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"type\": \"ABSOLUTE\"}",
            "{\"type\": \"ABSOLUTE\", \"at\": \"2026-10-23T11:30\", \"minuteOffset\": 5}",
            "{\"type\": \"RELATIVE\"}",
            "{\"type\": \"RELATIVE\", \"minuteOffset\": 5, \"at\": \"2026-10-23T11:30\"}"})
    void fieldsNotMatchingTheType_return400(String json) throws Exception {
        RegisteredUser user = frozenUser();
        UUID taskId = timedTask(user);

        postReminder(user, taskId, json)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(FIELDS_VI));
        assertThat(reminderCount(taskId)).isZero();
    }

    @Test
    void missingOrUnknownType_returns400() throws Exception {
        RegisteredUser user = frozenUser();
        UUID taskId = timedTask(user);

        postReminder(user, taskId, """
                {"at": "2026-10-23T11:30"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("type"))
                .andExpect(jsonPath("$.errors[0].message").value("Vui lòng chọn loại nhắc"));
        postReminder(user, taskId, """
                {"type": "LOCATION", "at": "2026-10-23T11:30"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    // ---- trạng thái task ------------------------------------------------------------------------------

    @Test
    void completedTask_returns409TaskCompleted() throws Exception {
        RegisteredUser user = frozenUser();
        UUID taskId = timedTask(user);
        authPost(user.accessToken(), "/api/v1/tasks/" + taskId + "/close").andExpect(status().isOk());

        postReminder(user, taskId, RELATIVE_30)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("task-completed"))
                .andExpect(jsonPath("$.detail").value("Công việc đã hoàn thành, không thêm nhắc được"));
    }

    @Test
    void archivedProject_canBeReadButNotWritten() throws Exception {
        RegisteredUser user = frozenUser();
        UUID projectId = project(user, "Dự án cũ");
        UUID taskId = createTask(user, """
                {"projectId": "%s", "content": "Việc trong dự án cũ", "dueDatetime": "2026-10-23T12:00:00"}
                """.formatted(projectId));
        String reminderId = readString(postReminder(user, taskId, RELATIVE_30).andExpect(status().isCreated()),
                "$.id");
        authPost(user.accessToken(), "/api/v1/projects/" + projectId + "/archive").andExpect(status().isOk());

        authGet(user.accessToken(), reminders(taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        postReminder(user, taskId, RELATIVE_30)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("project-archived"));
        authDelete(user.accessToken(), reminders(taskId) + "/" + reminderId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("project-archived"));
    }

    @Test
    void archivedSection_canBeReadButNotWritten() throws Exception {
        RegisteredUser user = frozenUser();
        UUID projectId = project(user, "Dự án có section");
        UUID sectionId = section(user, projectId, "Giai đoạn 1");
        UUID taskId = createTask(user, """
                {"projectId": "%s", "sectionId": "%s", "content": "Việc trong section",
                 "dueDatetime": "2026-10-23T12:00:00"}
                """.formatted(projectId, sectionId));
        String reminderId = readString(postReminder(user, taskId, RELATIVE_30).andExpect(status().isCreated()),
                "$.id");
        // Lưu trữ section: task đang mở của nó thành đã hoàn thành (spec 12.2) — vẫn phải ra section-archived,
        // không phải task-completed (TaskService.requireWritable chạy trước kiểm "đã xong").
        authPost(user.accessToken(), "/api/v1/projects/" + projectId + "/sections/" + sectionId + "/archive")
                .andExpect(status().isOk());

        authGet(user.accessToken(), reminders(taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        postReminder(user, taskId, RELATIVE_30)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("section-archived"));
        authDelete(user.accessToken(), reminders(taskId) + "/" + reminderId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("section-archived"));
    }

    @Test
    void taskOfAChildProject_isReadOnlyWhenTheParentProjectIsArchived() throws Exception {
        RegisteredUser user = frozenUser();
        UUID parentId = project(user, "Dự án cha");
        UUID childId = childProject(user, parentId, "Dự án con");
        UUID taskId = createTask(user, """
                {"projectId": "%s", "content": "Việc trong dự án con", "dueDatetime": "2026-10-23T12:00:00"}
                """.formatted(childId));
        postReminder(user, taskId, RELATIVE_30).andExpect(status().isCreated());
        authPost(user.accessToken(), "/api/v1/projects/" + parentId + "/archive") // lưu trữ kèm dự án con (spec 5.7)
                .andExpect(status().isOk());

        authGet(user.accessToken(), reminders(taskId)).andExpect(jsonPath("$.length()").value(1));
        postReminder(user, taskId, RELATIVE_30)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("project-archived"));
    }

    // ---- xoá ------------------------------------------------------------------------------------------

    @Test
    void delete_returns204_thenTheReminderIsGone() throws Exception {
        RegisteredUser user = frozenUser();
        UUID taskId = timedTask(user);
        String reminderId = readString(postReminder(user, taskId, RELATIVE_30).andExpect(status().isCreated()),
                "$.id");

        authDelete(user.accessToken(), reminders(taskId) + "/" + reminderId).andExpect(status().isNoContent());

        authGet(user.accessToken(), reminders(taskId)).andExpect(jsonPath("$.length()").value(0));
        authDelete(user.accessToken(), reminders(taskId) + "/" + reminderId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Không tìm thấy nhắc nhở"));
    }

    /** Task đã xong (trong dự án đang hoạt động) vẫn xoá được nhắc → 204, KHÔNG 409 (chỉ POST bị task-completed). */
    @Test
    void delete_onACompletedTask_stillReturns204() throws Exception {
        RegisteredUser user = frozenUser();
        UUID taskId = timedTask(user);
        String reminderId = readString(postReminder(user, taskId, RELATIVE_30).andExpect(status().isCreated()),
                "$.id");
        authPost(user.accessToken(), "/api/v1/tasks/" + taskId + "/close").andExpect(status().isOk());

        authDelete(user.accessToken(), reminders(taskId) + "/" + reminderId).andExpect(status().isNoContent());

        assertThat(reminderCount(taskId)).isZero();
    }

    @Test
    void delete_throughAnotherTaskPath_returns404() throws Exception {
        RegisteredUser user = frozenUser();
        UUID taskId = timedTask(user);
        UUID otherTaskId = timedTask(user);
        String reminderId = readString(postReminder(user, taskId, RELATIVE_30).andExpect(status().isCreated()),
                "$.id");

        authDelete(user.accessToken(), reminders(otherTaskId) + "/" + reminderId)
                .andExpect(status().isNotFound());
        assertThat(reminderCount(taskId)).isEqualTo(1);
    }

    // ---- quyền ----------------------------------------------------------------------------------------

    @Test
    void anotherUsersTask_returns404Everywhere() throws Exception {
        RegisteredUser owner = frozenUser();
        RegisteredUser stranger = registerUser(uniqueEmail());
        UUID taskId = timedTask(owner);
        String reminderId = readString(postReminder(owner, taskId, RELATIVE_30).andExpect(status().isCreated()),
                "$.id");

        authGet(stranger.accessToken(), reminders(taskId)).andExpect(status().isNotFound());
        postReminder(stranger, taskId, RELATIVE_30).andExpect(status().isNotFound());
        authDelete(stranger.accessToken(), reminders(taskId) + "/" + reminderId).andExpect(status().isNotFound());
        assertThat(reminderCount(taskId)).isEqualTo(1);
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get(reminders(UUID.randomUUID())))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    // ---- chống gửi trùng, giới hạn, i18n --------------------------------------------------------------

    @Test
    void sameIdempotencyKey_replaysTheFirstResult() throws Exception {
        RegisteredUser user = frozenUser();
        UUID taskId = timedTask(user);
        String key = UUID.randomUUID().toString();

        MvcResult first = mockMvc.perform(post(reminders(taskId))
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(ABSOLUTE_1130))
                .andExpect(status().isCreated())
                .andReturn();
        String firstId = JsonPath.read(body(first), "$.id");

        mockMvc.perform(post(reminders(taskId))
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(ABSOLUTE_1130))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.id").value(firstId));
        assertThat(reminderCount(taskId)).isEqualTo(1);
    }

    @Test
    void defaultReminderLimitIsSevenHundred() {
        assertThat(usageLimits.max(LimitKind.REMINDERS)).isEqualTo(700);
    }

    /**
     * Biên với max hạ còn 3 (tương ứng 700): dưới max → 201; vượt → 422 kèm limit + max; chỉ đếm nhắc trên task chưa
     * xong (đóng task thì số đếm giảm). check(REMINDERS, current, 1): current + 1 > max.
     */
    @Test
    void limit_boundaryAndOnlyOpenTasksAreCounted() throws Exception {
        RegisteredUser user = frozenUser();
        usageLimits.overrideMax(LimitKind.REMINDERS, 3);
        UUID full = timedTask(user);
        UUID other = timedTask(user);
        insertReminders(full, user.id(), 2);                                       // max − 1 (tương ứng 699)

        postReminder(user, other, RELATIVE_30).andExpect(status().isCreated());    // nhắc thứ max (700)
        postReminder(user, other, RELATIVE_30)                                      // nhắc thứ max + 1 (701)
                .andExpect(status().is(422))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("limit-reached"))
                .andExpect(jsonPath("$.limit").value("REMINDERS"))
                .andExpect(jsonPath("$.max").value(3));
        assertThat(reminderCount(other)).isEqualTo(1);

        authPost(user.accessToken(), "/api/v1/tasks/" + full + "/close").andExpect(status().isOk()); // 2 nhắc hết bị đếm

        postReminder(user, other, RELATIVE_30).andExpect(status().isCreated());
    }

    @Test
    void limit_isPerUser_anotherUserIsNotBlocked() throws Exception {
        RegisteredUser first = frozenUser();
        RegisteredUser second = registerUser(uniqueEmail());
        usageLimits.overrideMax(LimitKind.REMINDERS, 3);
        UUID firstsTask = timedTask(first);
        UUID secondsTask = timedTask(second);
        insertReminders(firstsTask, first.id(), 3);                                // first đã đầy

        postReminder(first, firstsTask, RELATIVE_30).andExpect(status().is(422));
        postReminder(second, secondsTask, RELATIVE_30).andExpect(status().isCreated());
    }
}
