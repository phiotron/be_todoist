package com.taskly.controller;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * GET /api/v1/tasks/reminders (spec 5.2, 5.8, 12.8): server tính fireAt (auto / tuyệt đối / tương đối) theo múi giờ
 * tài khoản, bỏ lần đã qua, bỏ trùng (cùng task + cùng fireAt, ưu tiên nhắc riêng), sắp theo fireAt, limit 1–50.
 * Task dựng qua API M1; nhắc riêng chèn bằng SQL.
 */
class TaskRemindersApiTest extends IntegrationTestBase {

    private static final String OCCURRENCES = "/api/v1/tasks/reminders";
    /** 10:00 ngày 23/10/2026 ở Asia/Ho_Chi_Minh (múi giờ mặc định của tài khoản). */
    private static final Instant TEN_AM_HCM = Instant.parse("2026-10-23T03:00:00Z");

    private RegisteredUser frozenUser() throws Exception {
        clock.freezeAt(TEN_AM_HCM);
        return registerUser(uniqueEmail());
    }

    private ResultActions occurrences(RegisteredUser user) throws Exception {
        return authGet(user.accessToken(), OCCURRENCES);
    }

    private UUID createTask(RegisteredUser user, String json) throws Exception {
        return readUuid(authPost(user.accessToken(), "/api/v1/tasks", json).andExpect(status().isCreated()), "$.id");
    }

    private UUID timedTask(RegisteredUser user, String content, String dueDatetime) throws Exception {
        return createTask(user, """
                {"content": "%s", "dueDatetime": "%s"}
                """.formatted(content, dueDatetime));
    }

    private UUID insertReminder(UUID taskId, UUID userId, String type, LocalDateTime at, Integer minuteOffset) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        jdbcTemplate.update("insert into reminders (id, task_id, user_id, type, at, minute_offset, created_at, "
                + "updated_at) values (?, ?, ?, ?, ?, ?, ?, ?)", id, taskId, userId, type, at, minuteOffset, now, now);
        return id;
    }

    private UUID absolute(RegisteredUser user, UUID taskId, String at) {
        return insertReminder(taskId, user.id(), "ABSOLUTE", LocalDateTime.parse(at), null);
    }

    private UUID relative(RegisteredUser user, UUID taskId, int minuteOffset) {
        return insertReminder(taskId, user.id(), "RELATIVE", null, minuteOffset);
    }

    private void setAutoReminderMinutes(UUID userId, Integer minutes) {
        jdbcTemplate.update("update users set auto_reminder_minutes = ? where id = ?", minutes, userId);
    }

    private void setTimeZone(UUID userId, String timeZone) {
        jdbcTemplate.update("update users set time_zone = ? where id = ?", timeZone, userId);
    }

    // ---- auto reminder ------------------------------------------------------------------------------

    @Test
    void timedTask_getsAnAutoReminderBeforeItsDueTime() throws Exception {
        RegisteredUser user = frozenUser();
        UUID taskId = timedTask(user, "Họp nhóm", "2026-10-23T12:00:00");

        occurrences(user)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].key").value("auto:" + taskId))
                .andExpect(jsonPath("$[0].taskId").value(taskId.toString()))
                .andExpect(jsonPath("$[0].reminderId").value(nullValue()))
                .andExpect(jsonPath("$[0].kind").value("AUTO"))
                .andExpect(jsonPath("$[0].fireAt").value("2026-10-23T11:30:00"))
                .andExpect(jsonPath("$[0].content").value("Họp nhóm"));
    }

    @Test
    void tasksWithoutTime_getNoAutoReminder() throws Exception {
        RegisteredUser user = frozenUser();
        createTask(user, """
                {"content": "Cả ngày hôm nay", "dueDate": "2026-10-23"}
                """);
        createTask(user, """
                {"content": "Cả ngày mai", "dueDate": "2026-10-24"}
                """);
        createTask(user, """
                {"content": "Không có hạn"}
                """);

        occurrences(user).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void autoReminderMinutes_nullTurnsItOff_zeroFiresAtTheDueTime() throws Exception {
        RegisteredUser user = frozenUser();
        timedTask(user, "Họp nhóm", "2026-10-23T12:00:00");

        setAutoReminderMinutes(user.id(), null);
        occurrences(user).andExpect(jsonPath("$.length()").value(0));

        setAutoReminderMinutes(user.id(), 0);
        occurrences(user)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].fireAt").value("2026-10-23T12:00:00"));
    }

    // ---- nhắc riêng ---------------------------------------------------------------------------------

    @Test
    void relativeAndAbsoluteReminders_areMergedWithAutoAndSortedByFireAt() throws Exception {
        RegisteredUser user = frozenUser();
        UUID meeting = timedTask(user, "Họp nhóm", "2026-10-23T12:00:00");
        UUID reading = createTask(user, """
                {"content": "Đọc sách"}
                """);
        UUID relativeId = relative(user, meeting, 60);
        UUID absoluteId = absolute(user, reading, "2026-10-24T09:00:00");

        occurrences(user)
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].kind").value("RELATIVE"))
                .andExpect(jsonPath("$[0].key").value(relativeId.toString()))
                .andExpect(jsonPath("$[0].reminderId").value(relativeId.toString()))
                .andExpect(jsonPath("$[0].taskId").value(meeting.toString()))
                .andExpect(jsonPath("$[0].fireAt").value("2026-10-23T11:00:00"))
                .andExpect(jsonPath("$[1].kind").value("AUTO"))
                .andExpect(jsonPath("$[1].fireAt").value("2026-10-23T11:30:00"))
                .andExpect(jsonPath("$[2].kind").value("ABSOLUTE"))
                .andExpect(jsonPath("$[2].key").value(absoluteId.toString()))
                .andExpect(jsonPath("$[2].taskId").value(reading.toString()))
                .andExpect(jsonPath("$[2].fireAt").value("2026-10-24T09:00:00"))
                .andExpect(jsonPath("$[2].content").value("Đọc sách"));
    }

    @Test
    void pastOccurrencesAreDropped_fireAtEqualToNowIsKept() throws Exception {
        RegisteredUser user = frozenUser();
        UUID soon = timedTask(user, "Sắp tới giờ", "2026-10-23T10:20:00"); // auto 30 → 09:50 đã qua
        relative(user, soon, 20);                                          // → 10:00:00 = đúng bây giờ

        occurrences(user)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].kind").value("RELATIVE"))
                .andExpect(jsonPath("$[0].fireAt").value("2026-10-23T10:00:00"));

        clock.advance(Duration.ofSeconds(1));

        occurrences(user).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void sameTaskSameFireAt_keepsOneCustomReminder_otherTasksAreKept() throws Exception {
        RegisteredUser user = frozenUser();
        UUID meeting = timedTask(user, "Họp nhóm", "2026-10-23T12:00:00"); // auto → 11:30
        UUID relativeId = relative(user, meeting, 30);                       // → 11:30, tạo trước
        clock.advance(Duration.ofSeconds(1));
        absolute(user, meeting, "2026-10-23T11:30:00");                       // → 11:30, tạo sau
        UUID call = timedTask(user, "Gọi điện", "2026-10-23T12:00:00");     // auto → 11:30, task khác

        occurrences(user)
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.taskId == '" + meeting + "')].kind", contains("RELATIVE")))
                .andExpect(jsonPath("$[?(@.taskId == '" + meeting + "')].key", contains(relativeId.toString())))
                .andExpect(jsonPath("$[?(@.taskId == '" + call + "')].kind", contains("AUTO")));
    }

    // ---- phạm vi ------------------------------------------------------------------------------------

    @Test
    void excludesCompletedTasks_archivedProjects_andOtherUsers() throws Exception {
        RegisteredUser owner = frozenUser();
        RegisteredUser other = registerUser(uniqueEmail());
        timedTask(owner, "Còn mở", "2026-10-23T12:00:00");
        UUID done = timedTask(owner, "Đã xong", "2026-10-23T12:00:00");
        absolute(owner, done, "2026-10-23T11:00:00");
        authPost(owner.accessToken(), "/api/v1/tasks/" + done + "/close").andExpect(status().isOk());
        UUID projectId = readUuid(authPost(owner.accessToken(), "/api/v1/projects", """
                {"name": "Dự án lưu trữ"}
                """).andExpect(status().isCreated()), "$.id");
        createTask(owner, """
                {"projectId": "%s", "content": "Trong dự án lưu trữ", "dueDatetime": "2026-10-23T12:00:00"}
                """.formatted(projectId));
        authPost(owner.accessToken(), "/api/v1/projects/" + projectId + "/archive").andExpect(status().isOk());
        timedTask(other, "Của người khác", "2026-10-23T12:00:00");

        occurrences(owner)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].content").value("Còn mở"));
        occurrences(other)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].content").value("Của người khác"));
    }

    /** Lưu trữ dự án cha kèm dự án con (spec 5.7) → task trong dự án con không còn nhắc. */
    @Test
    void excludesTasksOfAChildProjectWhoseParentIsArchived() throws Exception {
        RegisteredUser user = frozenUser();
        UUID parentId = readUuid(authPost(user.accessToken(), "/api/v1/projects", """
                {"name": "Dự án cha"}
                """).andExpect(status().isCreated()), "$.id");
        UUID childId = readUuid(authPost(user.accessToken(), "/api/v1/projects", """
                {"name": "Dự án con", "parentId": "%s"}
                """.formatted(parentId)).andExpect(status().isCreated()), "$.id");
        UUID inChild = createTask(user, """
                {"projectId": "%s", "content": "Trong dự án con", "dueDatetime": "2026-10-23T12:00:00"}
                """.formatted(childId));
        absolute(user, inChild, "2026-10-23T11:00:00");
        timedTask(user, "Còn mở", "2026-10-23T12:00:00");
        authPost(user.accessToken(), "/api/v1/projects/" + parentId + "/archive").andExpect(status().isOk());

        occurrences(user)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].content").value("Còn mở"));
    }

    /** Lưu trữ section: task đang mở của nó thành đã xong (spec 12.2) → không còn nhắc nào của section đó. */
    @Test
    void excludesTasksOfAnArchivedSection() throws Exception {
        RegisteredUser user = frozenUser();
        UUID projectId = readUuid(authPost(user.accessToken(), "/api/v1/projects", """
                {"name": "Dự án có section"}
                """).andExpect(status().isCreated()), "$.id");
        UUID sectionId = readUuid(authPost(user.accessToken(), "/api/v1/projects/" + projectId + "/sections", """
                {"name": "Giai đoạn 1"}
                """).andExpect(status().isCreated()), "$.id");
        UUID inSection = createTask(user, """
                {"projectId": "%s", "sectionId": "%s", "content": "Trong section",
                 "dueDatetime": "2026-10-23T12:00:00"}
                """.formatted(projectId, sectionId));
        absolute(user, inSection, "2026-10-23T11:00:00");
        timedTask(user, "Còn mở", "2026-10-23T12:00:00");
        authPost(user.accessToken(), "/api/v1/projects/" + projectId + "/sections/" + sectionId + "/archive")
                .andExpect(status().isOk());

        occurrences(user)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].content").value("Còn mở"));
    }

    @Test
    void remindersEnabledFalse_isAppliedByTheAppNotTheServer() throws Exception {
        RegisteredUser user = frozenUser();
        timedTask(user, "Họp nhóm", "2026-10-23T12:00:00");
        jdbcTemplate.update("update users set reminders_enabled = false where id = ?", user.id());

        occurrences(user).andExpect(jsonPath("$.length()").value(1));
    }

    // ---- sắp + limit --------------------------------------------------------------------------------

    @Test
    void sortedByFireAt_atMostFiftyByDefault_andHonoursSmallerLimit() throws Exception {
        RegisteredUser user = frozenUser();
        LocalDateTime base = LocalDateTime.parse("2026-10-23T11:00:00");
        for (int i = 51; i >= 0; i--) { // tạo ngược thứ tự để chắc server tự sắp
            timedTask(user, "Việc %02d".formatted(i), base.plusMinutes(i).toString() + ":00");
        }

        occurrences(user)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(50))
                .andExpect(jsonPath("$[0].content").value("Việc 00"))
                .andExpect(jsonPath("$[0].fireAt").value("2026-10-23T10:30:00"))
                .andExpect(jsonPath("$[49].content").value("Việc 49"));
        authGet(user.accessToken(), OCCURRENCES + "?limit=3")
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[2].content").value("Việc 02"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "51", "abc"})
    void limitOutsideOneToFifty_returns400(String limit) throws Exception {
        RegisteredUser user = frozenUser();

        authGet(user.accessToken(), OCCURRENCES + "?limit=" + limit)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    // ---- múi giờ ------------------------------------------------------------------------------------

    @Test
    void nowIsTakenInTheAccountTimeZone() throws Exception {
        RegisteredUser user = frozenUser(); // 03:00Z = 10:00 ngày 23 ở HCM = 23:00 ngày 22 ở New York
        timedTask(user, "Sáng mai ở New York", "2026-10-23T08:00:00"); // auto → 07:30 ngày 23

        occurrences(user).andExpect(jsonPath("$.length()").value(0)); // HCM: 07:30 đã qua

        setTimeZone(user.id(), "America/New_York");

        occurrences(user)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].fireAt").value("2026-10-23T07:30:00"));
    }

    @Test
    void fireAtIsFloating_changingTimeZoneDoesNotShiftIt() throws Exception {
        RegisteredUser user = frozenUser();
        timedTask(user, "Họp thứ Hai", "2026-10-26T09:00:00");

        occurrences(user).andExpect(jsonPath("$[0].fireAt").value("2026-10-26T08:30:00"));

        setTimeZone(user.id(), "Europe/London");

        occurrences(user).andExpect(jsonPath("$[0].fireAt").value("2026-10-26T08:30:00"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get(OCCURRENCES))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
