package com.taskly.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.IntegrationTestBase;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** V8__m6b_quick_add.sql: cột Quick Add của users, mặc định và CHECK định dạng (M6b). */
class QuickAddMigrationTest extends IntegrationTestBase {

    @Test
    void flywayAppliedVersion8() {
        assertThat(jdbcTemplate.queryForObject(
                "select success from flyway_schema_history where version = '8'", Boolean.class)).isTrue();
    }

    @Test
    void newUsersGetTheQuickAddDefaults() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        Map<String, Object> row = jdbcTemplate.queryForMap("select quick_add_chips, quick_add_show_action_labels, "
                + "quick_add_show_description from users where id = ?", user.id());
        assertThat(row).containsEntry("quick_add_chips", "PROJECT,DATE,ASSIGNEE")
                .containsEntry("quick_add_show_action_labels", true)
                .containsEntry("quick_add_show_description", true);
    }

    @Test
    void chipsColumnOnlyAcceptsCommaSeparatedChipNames() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        jdbcTemplate.update("update users set quick_add_chips = ? where id = ?", "PROJECT", user.id());
        jdbcTemplate.update("update users set quick_add_chips = ? where id = ?",
                "DATE,ASSIGNEE,ATTACHMENT,PRIORITY,REMINDERS,LABELS,DEADLINE,LOCATION,PROJECT", user.id());

        for (String bad : new String[] {"", "DATE", "PROJECT,", "PROJECT,COMMENTS", "project"}) {
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "update users set quick_add_chips = ? where id = ?", bad, user.id()))
                    .as(bad).isInstanceOf(DataIntegrityViolationException.class);
        }
    }
}
