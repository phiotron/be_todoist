package com.taskly.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ReminderService nghe TaskTimeClearedEvent của M1 (Quyết định #2, review I8): task mất giờ → xoá nhắc RELATIVE của
 * nó, CÙNG transaction với bên phát. Phát event trực tiếp để kiểm listener độc lập với các đường PATCH / batch
 * (đường đó do ReminderLifecycleApiTest kiểm).
 */
class ReminderTimeClearedListenerTest extends IntegrationTestBase {

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private UUID timedTask(RegisteredUser user) throws Exception {
        return readUuid(authPost(user.accessToken(), "/api/v1/tasks", """
                {"content": "Việc có giờ", "dueDatetime": "2026-10-30T12:00:00"}
                """).andExpect(status().isCreated()), "$.id");
    }

    private UUID insertReminder(UUID taskId, UUID userId, String type, LocalDateTime at, Integer minuteOffset) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        jdbcTemplate.update("insert into reminders (id, task_id, user_id, type, at, minute_offset, created_at, "
                + "updated_at) values (?, ?, ?, ?, ?, ?, ?, ?)", id, taskId, userId, type, at, minuteOffset, now, now);
        return id;
    }

    private int reminderCount(UUID taskId) {
        return jdbcTemplate.queryForObject("select count(*) from reminders where task_id = ?", Integer.class, taskId);
    }

    @Test
    void event_deletesRelativeRemindersOfTheListedTasksOnly() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID listed = timedTask(user);
        UUID other = timedTask(user);
        insertReminder(listed, user.id(), "RELATIVE", null, 30);
        insertReminder(listed, user.id(), "RELATIVE", null, 60);
        UUID absolute = insertReminder(listed, user.id(), "ABSOLUTE", LocalDateTime.parse("2026-10-30T09:00"), null);
        insertReminder(other, user.id(), "RELATIVE", null, 30);

        events.publishEvent(new TaskTimeClearedEvent(List.of(listed)));

        assertThat(jdbcTemplate.queryForList("select id from reminders where task_id = ?", UUID.class, listed))
                .containsExactly(absolute);
        assertThat(reminderCount(other)).isEqualTo(1);
    }

    @Test
    void event_withSeveralTasks_clearsAllOfThem() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID first = timedTask(user);
        UUID second = timedTask(user);
        insertReminder(first, user.id(), "RELATIVE", null, 10);
        insertReminder(second, user.id(), "RELATIVE", null, 10);

        events.publishEvent(new TaskTimeClearedEvent(List.of(first, second)));

        assertThat(reminderCount(first)).isZero();
        assertThat(reminderCount(second)).isZero();
    }

    @Test
    void emptyOrUnknownTaskIds_doNothing() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID taskId = timedTask(user);
        insertReminder(taskId, user.id(), "RELATIVE", null, 30);

        events.publishEvent(new TaskTimeClearedEvent(List.of()));
        events.publishEvent(new TaskTimeClearedEvent(List.of(UUID.randomUUID())));

        assertThat(reminderCount(taskId)).isEqualTo(1);
    }

    /** Cùng transaction với bên phát: bên phát lỗi → rollback, nhắc còn nguyên. */
    @Test
    void deletionRollsBackTogetherWithThePublishingTransaction() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID taskId = timedTask(user);
        insertReminder(taskId, user.id(), "RELATIVE", null, 30);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            events.publishEvent(new TaskTimeClearedEvent(List.of(taskId)));
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(reminderCount(taskId)).isEqualTo(1);
    }
}
