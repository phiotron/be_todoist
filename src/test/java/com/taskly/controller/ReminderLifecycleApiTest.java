package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.IntegrationTestBase;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Nhắc riêng đi theo thao tác task của M1 (spec 5.7, 5.8, 10.1): mất giờ → xoá nhắc tương đối (listener
 * TaskTimeClearedEvent của ReminderService, Quyết định #2); xoá task / section / dự án → xoá nhắc (FK cascade);
 * sao chép task → không chép nhắc.
 */
class ReminderLifecycleApiTest extends IntegrationTestBase {

    private static final Instant TEN_AM_HCM = Instant.parse("2026-10-23T03:00:00Z");

    private record TimedTask(UUID id, long version) {
    }

    private static String reminders(UUID taskId) {
        return "/api/v1/tasks/" + taskId + "/reminders";
    }

    private RegisteredUser frozenUser() throws Exception {
        clock.freezeAt(TEN_AM_HCM);
        return registerUser(uniqueEmail());
    }

    /** Task 12:00 + nhắc tương đối 30 phút + nhắc tuyệt đối 11:00. */
    private TimedTask timedTaskWithReminders(RegisteredUser user, String createJson) throws Exception {
        MvcResult created = authPost(user.accessToken(), "/api/v1/tasks", createJson)
                .andExpect(status().isCreated()).andReturn();
        String json = body(created);
        UUID id = UUID.fromString(JsonPath.read(json, "$.id"));
        long version = ((Number) JsonPath.read(json, "$.version")).longValue();
        authPost(user.accessToken(), reminders(id), """
                {"type": "RELATIVE", "minuteOffset": 30}
                """).andExpect(status().isCreated());
        authPost(user.accessToken(), reminders(id), """
                {"type": "ABSOLUTE", "at": "2026-10-23T11:00"}
                """).andExpect(status().isCreated());
        return new TimedTask(id, version);
    }

    private TimedTask timedTaskWithReminders(RegisteredUser user) throws Exception {
        return timedTaskWithReminders(user, """
                {"content": "Họp nhóm", "dueDatetime": "2026-10-23T12:00:00"}
                """);
    }

    private int reminderCount(UUID taskId) {
        return jdbcTemplate.queryForObject("select count(*) from reminders where task_id = ?", Integer.class, taskId);
    }

    @Test
    void patchToDateOnly_dropsRelativeKeepsAbsolute() throws Exception {
        RegisteredUser user = frozenUser();
        TimedTask task = timedTaskWithReminders(user);

        authPatch(user.accessToken(), "/api/v1/tasks/" + task.id(), """
                {"version": %d, "dueDate": "2026-10-24"}
                """.formatted(task.version())).andExpect(status().isOk());

        authGet(user.accessToken(), reminders(task.id()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("ABSOLUTE"));
    }

    @Test
    void patchRemovingDue_dropsRelative() throws Exception {
        RegisteredUser user = frozenUser();
        TimedTask task = timedTaskWithReminders(user);

        authPatch(user.accessToken(), "/api/v1/tasks/" + task.id(), """
                {"version": %d, "dueDatetime": null}
                """.formatted(task.version())).andExpect(status().isOk());

        authGet(user.accessToken(), reminders(task.id()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("ABSOLUTE"));
    }

    @Test
    void onlyTheTaskThatLostItsTime_losesItsRelativeReminders() throws Exception {
        RegisteredUser user = frozenUser();
        TimedTask losing = timedTaskWithReminders(user);
        TimedTask keeping = timedTaskWithReminders(user);

        authPatch(user.accessToken(), "/api/v1/tasks/" + losing.id(), """
                {"version": %d, "dueDate": "2026-10-24"}
                """.formatted(losing.version())).andExpect(status().isOk());

        authGet(user.accessToken(), reminders(losing.id())).andExpect(jsonPath("$.length()").value(1));
        authGet(user.accessToken(), reminders(keeping.id())).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void patchToAnotherTime_keepsRelative() throws Exception {
        RegisteredUser user = frozenUser();
        TimedTask task = timedTaskWithReminders(user);

        authPatch(user.accessToken(), "/api/v1/tasks/" + task.id(), """
                {"version": %d, "dueDatetime": "2026-10-23T15:00:00"}
                """.formatted(task.version())).andExpect(status().isOk());

        authGet(user.accessToken(), reminders(task.id())).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void batchScheduleWithoutDate_dropsRelative() throws Exception {
        RegisteredUser user = frozenUser();
        TimedTask task = timedTaskWithReminders(user);

        authPost(user.accessToken(), "/api/v1/tasks/batch", """
                {"action": "SCHEDULE", "taskIds": ["%s"], "date": null}
                """.formatted(task.id())).andExpect(status().isOk());

        authGet(user.accessToken(), reminders(task.id()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("ABSOLUTE"));
    }

    @Test
    void batchScheduleToAnotherDay_keepsTimeAndRelative() throws Exception {
        RegisteredUser user = frozenUser();
        TimedTask task = timedTaskWithReminders(user);

        authPost(user.accessToken(), "/api/v1/tasks/batch", """
                {"action": "SCHEDULE", "taskIds": ["%s"], "date": "2026-10-25"}
                """.formatted(task.id())).andExpect(status().isOk());

        authGet(user.accessToken(), reminders(task.id())).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void deleteTask_deletesItsReminders() throws Exception {
        RegisteredUser user = frozenUser();
        TimedTask task = timedTaskWithReminders(user);

        authDelete(user.accessToken(), "/api/v1/tasks/" + task.id()).andExpect(status().isNoContent());

        assertThat(reminderCount(task.id())).isZero();
    }

    @Test
    void duplicateTask_doesNotCopyReminders() throws Exception {
        RegisteredUser user = frozenUser();
        TimedTask task = timedTaskWithReminders(user);

        UUID copyId = readUuid(authPost(user.accessToken(), "/api/v1/tasks/" + task.id() + "/duplicate")
                .andExpect(status().is2xxSuccessful()), "$.id");

        authGet(user.accessToken(), reminders(copyId)).andExpect(jsonPath("$.length()").value(0));
        authGet(user.accessToken(), reminders(task.id())).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void deleteProject_deletesRemindersOfItsTasks() throws Exception {
        RegisteredUser user = frozenUser();
        UUID projectId = readUuid(authPost(user.accessToken(), "/api/v1/projects", """
                {"name": "Dự án sẽ xoá"}
                """).andExpect(status().isCreated()), "$.id");
        TimedTask task = timedTaskWithReminders(user, """
                {"projectId": "%s", "content": "Việc trong dự án", "dueDatetime": "2026-10-23T12:00:00"}
                """.formatted(projectId));

        authDelete(user.accessToken(), "/api/v1/projects/" + projectId).andExpect(status().isNoContent());

        assertThat(reminderCount(task.id())).isZero();
    }

    @Test
    void deleteSection_deletesRemindersOfItsTasks() throws Exception {
        RegisteredUser user = frozenUser();
        UUID projectId = readUuid(authPost(user.accessToken(), "/api/v1/projects", """
                {"name": "Dự án có section"}
                """).andExpect(status().isCreated()), "$.id");
        UUID sectionId = readUuid(authPost(user.accessToken(), "/api/v1/projects/" + projectId + "/sections", """
                {"name": "Giai đoạn 1"}
                """).andExpect(status().isCreated()), "$.id");
        TimedTask task = timedTaskWithReminders(user, """
                {"projectId": "%s", "sectionId": "%s", "content": "Việc trong section",
                 "dueDatetime": "2026-10-23T12:00:00"}
                """.formatted(projectId, sectionId));

        authDelete(user.accessToken(), "/api/v1/projects/" + projectId + "/sections/" + sectionId)
                .andExpect(status().isNoContent());

        assertThat(reminderCount(task.id())).isZero();
    }
}
