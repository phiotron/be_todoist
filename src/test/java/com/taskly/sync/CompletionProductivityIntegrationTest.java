package com.taskly.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Nối M10 (Activity + Karma) với M6c (task lặp nhảy kỳ, close forever) và M9 (sync task_close / task_add): mọi đường
 * hoàn thành đều đi qua TaskCompletionService → ProductivityEvents nên đều ghi COMPLETED + Karma đúng MỘT lần.
 */
class CompletionProductivityIntegrationTest extends SyncTestBase {

    private static final Instant NOW = Instant.parse("2026-10-27T03:00:00Z");

    private RegisteredUser user;
    private UUID project;

    @BeforeEach
    void setUp() throws Exception {
        clock.freezeAt(NOW);
        user = registerUser(uniqueEmail());
        project = testData.project(user.id(), "P");
    }

    private UUID recurringTask() throws Exception {
        return readUuid(authPost(user.accessToken(), "/api/v1/tasks", """
                {"projectId": "%s", "content": "Lặp", "dueDate": "2026-10-27", "recurrenceRule": "FREQ=DAILY"}
                """.formatted(project)).andExpect(status().isCreated()), "$.id");
    }

    private int completedActivity(UUID taskId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from activity_log where object_id = ? and event_type = 'COMPLETED'", Integer.class, taskId);
    }

    private int karmaCompletions(UUID taskId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from karma_events where task_id = ? and reason = 'TASK_COMPLETED'", Integer.class, taskId);
    }

    @Test
    void recurringClose_advancesDue_butStillRecordsActivityAndKarma() throws Exception {
        UUID id = recurringTask();

        authPost(user.accessToken(), "/api/v1/tasks/" + id + "/close", "{}").andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject("select completed_at from tasks where id = ?", Object.class, id)).isNull();
        assertThat(completedActivity(id)).isEqualTo(1);
        assertThat(karmaCompletions(id)).isEqualTo(1);
    }

    @Test
    void recurringCloseForever_recordsActivityAndKarmaOnce() throws Exception {
        UUID id = recurringTask();

        authPost(user.accessToken(), "/api/v1/tasks/" + id + "/close", "{\"forever\": true}").andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject("select completed_at from tasks where id = ?", Object.class, id))
                .isNotNull();
        assertThat(completedActivity(id)).isEqualTo(1);
        assertThat(karmaCompletions(id)).isEqualTo(1);
    }

    @Test
    void batchClose_ofRecurringTask_recordsOnce() throws Exception {
        UUID id = recurringTask();

        authPost(user.accessToken(), "/api/v1/tasks/batch",
                "{\"action\": \"CLOSE\", \"taskIds\": [\"" + id + "\"]}").andExpect(status().isOk());

        assertThat(completedActivity(id)).isEqualTo(1);
        assertThat(karmaCompletions(id)).isEqualTo(1);
    }

    @Test
    void syncTaskAddAndClose_writeActivity() throws Exception {
        UUID taskId = UUID.randomUUID();
        String body = syncOk(user.accessToken(), request("*",
                cmd(UUID.randomUUID(), "task_add", taskId.toString(),
                        "{\"content\": \"offline\", \"projectId\": \"" + project + "\"}"),
                cmd("task_close", "{\"id\": \"" + taskId + "\"}")));

        assertThat(okStatuses(body)).isEqualTo("ok,ok");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from activity_log where object_id = ? and event_type = 'ADDED'", Integer.class, taskId))
                .isEqualTo(1);
        assertThat(completedActivity(taskId)).isEqualTo(1);
        assertThat(karmaCompletions(taskId)).isEqualTo(1);
    }
}
