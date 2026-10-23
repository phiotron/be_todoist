package com.taskly.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** Kiểm tra trực tiếp bằng SQL: bảng và ràng buộc do V1__m0_auth_workspace.sql tạo. */
class MigrationSchemaTest extends IntegrationTestBase {

    @Test
    void flywayAppliedVersion1() {
        Boolean success = jdbcTemplate.queryForObject(
                "select success from flyway_schema_history where version = '1'", Boolean.class);
        assertThat(success).isTrue();
    }

    @Test
    void createsAllM0Tables() {
        List<String> tables = jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);
        assertThat(tables).contains("users", "refresh_tokens", "workspaces", "workspace_members", "projects");
    }

    @Test
    void usersHaveVietnameseDefaultsAndUniqueEmail() {
        String email = uniqueEmail();
        UUID userId = insertUser(email);

        Map<String, Object> row = jdbcTemplate.queryForMap("select locale, time_zone from users where id = ?", userId);
        assertThat(row).containsEntry("locale", "vi").containsEntry("time_zone", "Asia/Ho_Chi_Minh");
        assertThatThrownBy(() -> insertUser(email)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlyOnePersonalWorkspacePerOwner() {
        UUID owner = insertUser(uniqueEmail());
        insertWorkspace(owner, "PERSONAL");
        insertWorkspace(owner, "TEAM");
        insertWorkspace(owner, "TEAM");

        assertThatThrownBy(() -> insertWorkspace(owner, "PERSONAL"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlyOneInboxPerWorkspace() {
        UUID workspace = insertWorkspace(insertUser(uniqueEmail()), "PERSONAL");
        insertProject(workspace, true);
        insertProject(workspace, false);
        insertProject(workspace, false);

        Map<String, Object> defaults = jdbcTemplate.queryForMap(
                "select view_style, sort_order from projects where workspace_id = ? and is_inbox", workspace);
        assertThat(defaults).containsEntry("view_style", "LIST").containsEntry("sort_order", 0);
        assertThatThrownBy(() -> insertProject(workspace, true))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void checkConstraintsRejectUnknownEnumValues() {
        UUID owner = insertUser(uniqueEmail());
        UUID workspace = insertWorkspace(owner, "TEAM");

        assertThatThrownBy(() -> insertWorkspace(owner, "OTHER"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into workspace_members (id, workspace_id, user_id, role, created_at, updated_at) "
                        + "values (?, ?, ?, 'OWNER', now(), now())",
                UUID.randomUUID(), workspace, owner))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into projects (id, workspace_id, name, view_style, created_at, updated_at) "
                        + "values (?, ?, 'P', 'GRID', now(), now())",
                UUID.randomUUID(), workspace))
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

    private UUID insertWorkspace(UUID ownerId, String type) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "insert into workspaces (id, name, type, owner_id, created_at, updated_at) "
                        + "values (?, 'WS', ?, ?, now(), now())",
                id, type, ownerId);
        return id;
    }

    private void insertProject(UUID workspaceId, boolean inbox) {
        jdbcTemplate.update(
                "insert into projects (id, workspace_id, name, is_inbox, created_at, updated_at) "
                        + "values (?, ?, 'P', ?, now(), now())",
                UUID.randomUUID(), workspaceId, inbox);
    }
}
