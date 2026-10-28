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

/** V16__m12_android.sql: cài đặt nhắc M12 trên users, bảng locations, nhắc LOCATION + cờ urgent trên reminders. */
class M12MigrationTest extends IntegrationTestBase {

    private UUID timedTask(RegisteredUser user) throws Exception {
        return readUuid(authPost(user.accessToken(), "/api/v1/tasks", """
                {"content": "Việc có giờ", "dueDatetime": "2026-10-30T12:00:00"}
                """).andExpect(status().isCreated()), "$.id");
    }

    private UUID insertLocation(UUID userId, String name, double lat, double lng, int radius) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        jdbcTemplate.update("insert into locations (id, user_id, name, latitude, longitude, radius_m, created_at, "
                + "updated_at) values (?, ?, ?, ?, ?, ?, ?, ?)", id, userId, name, lat, lng, radius, now, now);
        return id;
    }

    private UUID insertReminder(UUID taskId, UUID userId, String type, LocalDateTime at, Integer minuteOffset,
                                UUID locationId, String trigger, boolean urgent) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        jdbcTemplate.update("insert into reminders (id, task_id, user_id, type, at, minute_offset, location_id, "
                        + "location_trigger, urgent, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, taskId, userId, type, at, minuteOffset, locationId, trigger, urgent, now, now);
        return id;
    }

    private int reminderCount(UUID taskId) {
        return jdbcTemplate.queryForObject("select count(*) from reminders where task_id = ?", Integer.class, taskId);
    }

    @Test
    void flywayAppliedVersion16() {
        Boolean success = jdbcTemplate.queryForObject(
                "select success from flyway_schema_history where version = '16'", Boolean.class);
        assertThat(success).isTrue();
    }

    @Test
    void newUserGetsM12Defaults() throws Exception {
        UUID userId = registerUser(uniqueEmail()).id();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select snooze_minutes, pin_reminders, urgent_device_id from users where id = ?", userId);
        assertThat(row).containsEntry("snooze_minutes", 15).containsEntry("pin_reminders", false)
                .containsEntry("urgent_device_id", null);
    }

    @Test
    void snoozeMinutesAcceptsOnlyTheList() throws Exception {
        UUID userId = registerUser(uniqueEmail()).id();

        for (int allowed : new int[] {5, 10, 15, 30, 45, 60, 120, 180}) {
            jdbcTemplate.update("update users set snooze_minutes = ? where id = ?", allowed, userId);
        }
        for (Integer rejected : new Integer[] {0, 7, 1440, null}) {
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "update users set snooze_minutes = ? where id = ?", rejected, userId))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void locationsCheckRangesAndBelongToTheirUser() throws Exception {
        UUID userId = registerUser(uniqueEmail()).id();

        insertLocation(userId, "Nhà", 10.776, 106.700, 100);
        insertLocation(userId, "Biên", -90, 180, 5000);
        assertThatThrownBy(() -> insertLocation(userId, "Lat", 90.5, 0, 100))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertLocation(userId, "Lng", 0, -180.5, 100))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertLocation(userId, "Nhỏ", 0, 0, 99))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertLocation(userId, "Lớn", 0, 0, 5001))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertLocation(userId, " ", 0, 0, 100))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.queryForObject("select confdeltype from pg_constraint "
                + "where conname = 'fk_locations_user'", String.class)).isEqualTo("c");
    }

    @Test
    void locationRemindersNeedALocationAndATrigger() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID taskId = timedTask(user);
        UUID locationId = insertLocation(user.id(), "Văn phòng", 10.78, 106.70, 200);
        LocalDateTime at = LocalDateTime.parse("2026-10-30T11:00:00");

        insertReminder(taskId, user.id(), "LOCATION", null, null, locationId, "ON_ENTER", false);
        insertReminder(taskId, user.id(), "LOCATION", null, null, locationId, "ON_LEAVE", false);
        insertReminder(taskId, user.id(), "ABSOLUTE", at, null, null, null, true);
        insertReminder(taskId, user.id(), "RELATIVE", null, 30, null, null, true);
        assertThat(reminderCount(taskId)).isEqualTo(4);

        assertThatThrownBy(() -> insertReminder(taskId, user.id(), "LOCATION", null, null, null, "ON_ENTER", false))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertReminder(taskId, user.id(), "LOCATION", null, null, locationId, null, false))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertReminder(taskId, user.id(), "LOCATION", null, null, locationId, "NEAR", false))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertReminder(taskId, user.id(), "LOCATION", at, null, locationId, "ON_ENTER", false))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertReminder(taskId, user.id(), "LOCATION", null, null, locationId, "ON_ENTER", true))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertReminder(taskId, user.id(), "ABSOLUTE", at, null, locationId, null, false))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertReminder(taskId, user.id(), "RELATIVE", null, 30, null, "ON_LEAVE", false))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(reminderCount(taskId)).isEqualTo(4);
    }

    @Test
    void existingReminderRowsDefaultToNotUrgent() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID taskId = timedTask(user);
        Timestamp now = Timestamp.from(clock.instant());
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("insert into reminders (id, task_id, user_id, type, at, minute_offset, created_at, "
                + "updated_at) values (?, ?, ?, 'RELATIVE', null, 15, ?, ?)", id, taskId, user.id(), now, now);

        assertThat(jdbcTemplate.queryForObject("select urgent from reminders where id = ?", Boolean.class, id))
                .isFalse();
    }

    @Test
    void deletingALocationDeletesItsReminders() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID taskId = timedTask(user);
        UUID locationId = insertLocation(user.id(), "Siêu thị", 10.70, 106.60, 300);
        insertReminder(taskId, user.id(), "LOCATION", null, null, locationId, "ON_ENTER", false);
        insertReminder(taskId, user.id(), "RELATIVE", null, 10, null, null, false);

        jdbcTemplate.update("delete from locations where id = ?", locationId);

        assertThat(reminderCount(taskId)).isEqualTo(1);
    }
}
