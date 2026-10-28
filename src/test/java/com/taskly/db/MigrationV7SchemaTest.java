package com.taskly.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.IntegrationTestBase;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** Cột cài đặt điều hướng do V7__m3x_navigation.sql (M3x) thêm vào users: mặc định và CHECK. */
class MigrationV7SchemaTest extends IntegrationTestBase {

    @Test
    void flywayAppliedVersion7() {
        Boolean success = jdbcTemplate.queryForObject(
                "select success from flyway_schema_history where version = '7'", Boolean.class);
        assertThat(success).isTrue();
    }

    @Test
    void navigationColumnsHaveDefaults() {
        UUID userId = insertUser(uniqueEmail());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select add_button_placement, sync_home_view, nav_menu_show_task_count, nav_menu_items "
                        + "from users where id = ?", userId);
        assertThat(row).containsEntry("add_button_placement", "RIGHT")
                .containsEntry("sync_home_view", true)
                .containsEntry("nav_menu_show_task_count", true)
                .containsEntry("nav_menu_items", "INBOX,TODAY,UPCOMING,FILTERS_LABELS,REPORTING,BROWSE_TEMPLATES");
    }

    @Test
    void navigationColumnsOnlyAcceptKnownValues() {
        UUID userId = insertUser(uniqueEmail());

        update(userId, "add_button_placement", "LEFT");
        update(userId, "add_button_placement", "CENTER");
        update(userId, "nav_menu_items", "");
        update(userId, "nav_menu_items", "REPORTING");
        update(userId, "nav_menu_items", "BROWSE_TEMPLATES,UPCOMING,TODAY");

        rejects(userId, "add_button_placement", "left");
        rejects(userId, "add_button_placement", "MIDDLE");
        rejects(userId, "nav_menu_items", "BROWSE");
        rejects(userId, "nav_menu_items", "INBOX,");
        rejects(userId, "nav_menu_items", ",INBOX");
        rejects(userId, "nav_menu_items", "inbox");
        assertThatThrownBy(() -> update(userId, "sync_home_view", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void update(UUID userId, String column, Object value) {
        jdbcTemplate.update("update users set " + column + " = ? where id = ?", value, userId);
    }

    private void rejects(UUID userId, String column, Object value) {
        assertThatThrownBy(() -> update(userId, column, value))
                .as("%s = %s phải bị CHECK chặn", column, value)
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID insertUser(String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "insert into users (id, email, password_hash, display_name, created_at, updated_at) "
                        + "values (?, ?, 'hash-not-used', 'Test', now(), now())",
                id, email);
        return id;
    }
}
