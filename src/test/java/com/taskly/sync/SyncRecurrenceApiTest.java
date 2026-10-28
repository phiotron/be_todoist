package com.taskly.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Task lặp (M6c) đi qua POST /api/v1/sync (M9): task_add mang recurrenceRule / dueString / recurrenceFromCompletion rồi
 * task_close → due nhảy kỳ, KHÔNG đặt completed_at, ghi activity COMPLETED + karma đúng một lần cho mỗi lần đóng.
 * "Bây giờ" = 2026-10-27T03:00Z = Thứ Ba 27/10 theo múi giờ tài khoản mặc định Asia/Ho_Chi_Minh.
 */
class SyncRecurrenceApiTest extends SyncTestBase {

    private static final Instant NOW = Instant.parse("2026-10-27T03:00:00Z");

    private RegisteredUser user;
    private UUID project;

    @BeforeEach
    void setUp() throws Exception {
        clock.freezeAt(NOW);
        user = registerUser(uniqueEmail());
        project = testData.project(user.id(), "P");
    }

    private String add(UUID taskId, String extraFields) {
        return cmd(UUID.randomUUID(), "task_add", taskId.toString(),
                "{\"content\": \"Lặp qua sync\", \"projectId\": \"" + project + "\", " + extraFields + "}");
    }

    private String close(UUID taskId) {
        return cmd("task_close", "{\"id\": \"" + taskId + "\"}");
    }

    private LocalDate dueDate(UUID id) {
        return jdbcTemplate.queryForObject("select due_date from tasks where id = ?", Date.class, id).toLocalDate();
    }

    private Object completedAt(UUID id) {
        return jdbcTemplate.queryForObject("select completed_at from tasks where id = ?", Object.class, id);
    }

    private int completedActivity(UUID id) {
        return jdbcTemplate.queryForObject(
                "select count(*) from activity_log where object_id = ? and event_type = 'COMPLETED'", Integer.class, id);
    }

    private int karmaCompletions(UUID id) {
        return jdbcTemplate.queryForObject(
                "select count(*) from karma_events where task_id = ? and reason = 'TASK_COMPLETED'", Integer.class, id);
    }

    @Test
    void taskAddWithRuleAndDueString_thenTaskClose_movesTheDueToTheNextOccurrence() throws Exception {
        UUID id = UUID.randomUUID();

        String body = syncOk(user.accessToken(), request("*",
                add(id, "\"dueDate\": \"2026-10-27\", \"recurrenceRule\": \"FREQ=WEEKLY;BYDAY=TU\", "
                        + "\"dueString\": \"every tuesday\""),
                close(id)));

        assertThat(okStatuses(body)).isEqualTo("ok,ok");
        assertThat(JsonPath.<String>read(body, "$.tempIdMapping['" + id + "']")).isEqualTo(id.toString());
        assertThat(dueDate(id)).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(completedAt(id)).isNull();
        assertThat(jdbcTemplate.queryForObject("select recurrence_rule from tasks where id = ?", String.class, id))
                .contains("FREQ=WEEKLY");
        assertThat(jdbcTemplate.queryForObject("select due_string from tasks where id = ?", String.class, id))
                .isEqualTo("every tuesday");
        assertThat(completedActivity(id)).isEqualTo(1);
        assertThat(karmaCompletions(id)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from activity_log where object_id = ? and event_type = 'ADDED'",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    void recurrenceFromCompletion_countsFromTheDayOfCompletion() throws Exception {
        UUID id = UUID.randomUUID();

        String body = syncOk(user.accessToken(), request("*",
                add(id, "\"dueDate\": \"2026-10-20\", \"recurrenceRule\": \"FREQ=DAILY;INTERVAL=3\", "
                        + "\"recurrenceFromCompletion\": true, \"dueString\": \"every! 3 days\""),
                close(id)));

        assertThat(okStatuses(body)).isEqualTo("ok,ok");
        assertThat(dueDate(id)).isEqualTo(LocalDate.of(2026, 10, 30)); // 27/10 + 3 ngày, không phải kỳ 20/10 + 3
        assertThat(completedAt(id)).isNull();
        assertThat(jdbcTemplate.queryForObject("select recurrence_from_completion from tasks where id = ?",
                Boolean.class, id)).isTrue();
        assertThat(completedActivity(id)).isEqualTo(1);
        assertThat(karmaCompletions(id)).isEqualTo(1);
    }

    @Test
    void eachTaskCloseAdvancesOnce_andRecordsActivityAndKarmaOncePerClose() throws Exception {
        UUID id = UUID.randomUUID();

        syncOk(user.accessToken(), request("*", add(id, "\"dueDate\": \"2026-10-27\", \"recurrenceRule\": \"FREQ=DAILY\"")));
        String first = syncOk(user.accessToken(), request("*", close(id)));
        assertThat(okStatuses(first)).isEqualTo("ok");
        assertThat(dueDate(id)).isEqualTo(LocalDate.of(2026, 10, 28));
        assertThat(completedActivity(id)).isEqualTo(1);
        assertThat(karmaCompletions(id)).isEqualTo(1);

        String second = syncOk(user.accessToken(), request("*", close(id)));
        assertThat(okStatuses(second)).isEqualTo("ok");
        assertThat(dueDate(id)).isEqualTo(LocalDate.of(2026, 10, 29));
        assertThat(completedAt(id)).isNull();
        assertThat(completedActivity(id)).isEqualTo(2);
        assertThat(karmaCompletions(id)).isEqualTo(2);
    }

    @Test
    void replayingTheSameCloseCommandUuid_doesNotAdvanceAgain() throws Exception {
        UUID id = UUID.randomUUID();
        syncOk(user.accessToken(), request("*", add(id, "\"dueDate\": \"2026-10-27\", \"recurrenceRule\": \"FREQ=DAILY\"")));
        String closeCommand = close(id);

        syncOk(user.accessToken(), request("*", closeCommand));
        String replay = syncOk(user.accessToken(), request("*", closeCommand));

        assertThat(okStatuses(replay)).isEqualTo("ok");
        assertThat(dueDate(id)).isEqualTo(LocalDate.of(2026, 10, 28));
        assertThat(completedActivity(id)).isEqualTo(1);
        assertThat(karmaCompletions(id)).isEqualTo(1);
    }

    @Test
    void taskAddWithInvalidRule_orRuleWithoutDue_isRejectedPerCommandAndCreatesNothing() throws Exception {
        UUID badRule = UUID.randomUUID();
        UUID noDue = UUID.randomUUID();

        String body = syncOk(user.accessToken(), request("*",
                add(badRule, "\"dueDate\": \"2026-10-27\", \"recurrenceRule\": \"FREQ=DAILY;COUNT=3\""),
                add(noDue, "\"recurrenceRule\": \"FREQ=DAILY\"")));

        assertThat(okStatuses(body)).isEqualTo("error,error");
        assertThat(JsonPath.<String>read(body, "$.results[0].error.code")).isEqualTo("recurrence-invalid");
        assertThat(JsonPath.<String>read(body, "$.results[1].error.code")).isEqualTo("recurrence-requires-due");
        assertThat(jdbcTemplate.queryForObject("select count(*) from tasks where id in (?, ?)", Integer.class,
                badRule, noDue)).isZero();
    }
}
