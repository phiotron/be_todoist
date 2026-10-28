package com.taskly.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.IntegrationTestBase;
import com.taskly.support.TestData.TaskRow;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** V9__m6c_recurrence.sql: cột mới của tasks / users, mặc định, CHECK "rule cần due" và "múi giờ cần giờ". */
class RecurrenceMigrationTest extends IntegrationTestBase {

    @Test
    void flywayAppliedVersion9() {
        assertThat(jdbcTemplate.queryForObject("select success from flyway_schema_history where version = '9'",
                Boolean.class)).isTrue();
    }

    @Test
    void newRowsGetTheDefaults() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID task = testData.task(user.id(), testData.inboxOf(user.id()), "x");

        assertThat(jdbcTemplate.queryForObject("select reset_subtasks from users where id = ?", Boolean.class, user.id()))
                .isFalse();
        Map<String, Object> row = jdbcTemplate.queryForMap("select recurrence_from_completion, due_timezone, "
                + "recurrence_undo_batch_id, recurrence_undo_due_date, recurrence_undo_due_datetime, "
                + "recurrence_undo_completed_at from tasks where id = ?", task);
        assertThat(row.get("recurrence_from_completion")).isEqualTo(false);
        assertThat(row.values().stream().skip(1)).allMatch(java.util.Objects::isNull);
    }

    @Test
    void checks_ruleNeedsADue_andFixedZoneNeedsATime() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID inbox = testData.inboxOf(user.id());
        UUID dated = testData.insert(TaskRow.of(user.id(), inbox, "x").dueDate(LocalDate.of(2026, 10, 27)));
        UUID undated = testData.task(user.id(), inbox, "y");

        jdbcTemplate.update("update tasks set recurrence_rule = 'FREQ=DAILY' where id = ?", dated);
        assertThatThrownBy(() -> jdbcTemplate.update("update tasks set recurrence_rule = 'FREQ=DAILY' where id = ?", undated))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("update tasks set due_timezone = 'UTC' where id = ?", dated))
                .isInstanceOf(DataIntegrityViolationException.class);
        // task lặp có deadline: không có CHECK nào chặn (bản 3.2)
        jdbcTemplate.update("update tasks set deadline_date = date '2026-12-01' where id = ?", dated);
    }
}
