package com.taskly.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** V5__m4_reminders.sql (spec 10, 5.8): cột cài đặt, bảng reminders, CHECK, FK cascade. Task dựng qua API M1; nhắc chèn bằng SQL. */
class ReminderMigrationTest extends IntegrationTestBase {

    private UUID timedTask(RegisteredUser user, String dueDatetime) throws Exception {
        return readUuid(authPost(user.accessToken(), "/api/v1/tasks", """
                {"content": "Việc có giờ", "dueDatetime": "%s"}
                """.formatted(dueDatetime)).andExpect(status().isCreated()), "$.id");
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
    void flywayAppliedVersion5() {
        Boolean success = jdbcTemplate.queryForObject(
                "select success from flyway_schema_history where version = '5'", Boolean.class);
        assertThat(success).isTrue();
    }

    @Test
    void newUserGetsReminderDefaults() throws Exception {
        UUID userId = registerUser(uniqueEmail()).id();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select reminders_enabled, auto_reminder_minutes from users where id = ?", userId);
        assertThat(row).containsEntry("reminders_enabled", true).containsEntry("auto_reminder_minutes", 30);
    }

    @Test
    void autoReminderMinutesAcceptsOnlyTheListOrNull() throws Exception {
        UUID userId = registerUser(uniqueEmail()).id();

        for (int allowed : new int[] {0, 5, 10, 15, 30, 45, 60, 120, 180, 1440}) {
            jdbcTemplate.update("update users set auto_reminder_minutes = ? where id = ?", allowed, userId);
        }
        jdbcTemplate.update("update users set auto_reminder_minutes = null where id = ?", userId);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "update users set auto_reminder_minutes = 7 where id = ?", userId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "update users set auto_reminder_minutes = -5 where id = ?", userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void reminderRowsMustMatchTheirType() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID taskId = timedTask(user, "2026-10-30T12:00:00");
        LocalDateTime at = LocalDateTime.parse("2026-10-30T11:00:00");

        insertReminder(taskId, user.id(), "ABSOLUTE", at, null);
        insertReminder(taskId, user.id(), "RELATIVE", null, 0);
        insertReminder(taskId, user.id(), "RELATIVE", null, 10080);
        assertThat(reminderCount(taskId)).isEqualTo(3);

        assertThatThrownBy(() -> insertReminder(taskId, user.id(), "ABSOLUTE", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertReminder(taskId, user.id(), "ABSOLUTE", at, 5))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertReminder(taskId, user.id(), "RELATIVE", at, 5))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertReminder(taskId, user.id(), "RELATIVE", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertReminder(taskId, user.id(), "RELATIVE", null, -1))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertReminder(taskId, user.id(), "RELATIVE", null, 10081))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertReminder(taskId, user.id(), "LOCATION", at, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(reminderCount(taskId)).isEqualTo(3);
    }

    @Test
    void deletingTheTaskDeletesItsReminders() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID taskId = timedTask(user, "2026-10-30T12:00:00");
        insertReminder(taskId, user.id(), "RELATIVE", null, 30);

        jdbcTemplate.update("delete from tasks where id = ?", taskId);

        assertThat(reminderCount(taskId)).isZero();
    }

    @Test
    void noTriggerOnTasks_dueTimeCleanupLivesInTheService() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID taskId = timedTask(user, "2026-10-30T12:00:00");
        insertReminder(taskId, user.id(), "RELATIVE", null, 30);

        // Ghi thẳng bằng SQL: không đi qua TaskService.applyDue nên không có event → nhắc còn nguyên (Quyết định #2).
        jdbcTemplate.update("update tasks set due_datetime = null, due_date = date '2026-10-30' where id = ?", taskId);

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from information_schema.triggers where event_object_table = 'tasks' "
                        + "and trigger_name like '%reminder%'", Integer.class)).isZero();
        assertThat(reminderCount(taskId)).isEqualTo(1);
    }
}
