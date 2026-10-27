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
        assertThat(defaults).containsEntry("view_style", "LIST").containsEntry("sort_order", 0L);
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
                "insert into projects (id, workspace_id, name, color, view_style, created_at, updated_at) "
                        + "values (?, ?, 'P', 'charcoal', 'GRID', now(), now())",
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
        insertProject(UUID.randomUUID(), workspaceId, null, inbox);
    }

    private UUID insertProject(UUID id, UUID workspaceId, UUID parentId, boolean inbox) {
        jdbcTemplate.update(
                "insert into projects (id, workspace_id, parent_id, name, color, is_inbox, created_at, updated_at) "
                        + "values (?, ?, ?, 'P', ?, ?, now(), now())",
                id, workspaceId, parentId, inbox ? null : "charcoal", inbox);
        return id;
    }

    private UUID insertTask(UUID projectId, UUID sectionId, UUID parentId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("insert into tasks (id, workspace_id, project_id, section_id, parent_id, content, "
                + "sort_order, created_at, updated_at) values (?, (select workspace_id from projects where id = ?), "
                + "?, ?, ?, 'T', 1024, now(), now())", id, projectId, projectId, sectionId, parentId);
        return id;
    }

    @Test
    void flywayAppliedM1Migration() {
        Boolean success = jdbcTemplate.queryForObject(
                "select success from flyway_schema_history where version = '2'", Boolean.class);
        assertThat(success).isTrue();
    }

    @Test
    void createsM1TablesAndColumns_withoutTrashColumns() {
        List<String> tables = jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);
        assertThat(tables).contains("sections", "tasks", "favorites", "idempotency_keys")
                .doesNotContain("trash_batches");

        String sortOrderType = jdbcTemplate.queryForObject(
                "select data_type from information_schema.columns where table_schema = 'public' and table_name = 'projects' and column_name = 'sort_order'",
                String.class);
        assertThat(sortOrderType).isEqualTo("bigint");

        List<String> projectColumns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns where table_schema = 'public' and table_name = 'projects'", String.class);
        assertThat(projectColumns).contains("parent_id", "description").doesNotContain("icon", "deleted_at");

        List<String> sectionColumns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns where table_schema = 'public' and table_name = 'sections'", String.class);
        assertThat(sectionColumns).contains("description", "archived_at").doesNotContain("deleted_at");

        List<String> taskColumns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns where table_schema = 'public' and table_name = 'tasks'", String.class);
        assertThat(taskColumns).contains("version", "recurrence_rule", "due_string", "creator_id", "due_day", "due_sort",
                "duration_minutes", "deadline_date", "workspace_id", "completion_batch_id")
                .doesNotContain("deleted_at", "delete_batch_id");

        String dueDatetimeType = jdbcTemplate.queryForObject(
                "select data_type from information_schema.columns where table_schema = 'public' and table_name = 'tasks' and column_name = 'due_datetime'",
                String.class);
        assertThat(dueDatetimeType).isEqualTo("timestamp without time zone"); // floating (spec 5.2)

        List<String> tokenColumns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns where table_schema = 'public' and table_name = 'refresh_tokens'", String.class);
        assertThat(tokenColumns).contains("family_id");
        assertThat(jdbcTemplate.queryForObject("select is_nullable from information_schema.columns "
                + "where table_schema = 'public' and table_name = 'refresh_tokens' and column_name = 'family_id'", String.class)).isEqualTo("NO");
    }

    @Test
    void tasksAndProjectsHaveIndexesFromSpec() {
        List<String> taskIndexes = jdbcTemplate.queryForList(
                "select indexname from pg_indexes where schemaname = 'public' and tablename = 'tasks'", String.class);
        assertThat(taskIndexes).contains("ix_tasks_container", "ix_tasks_parent_id", "ix_tasks_workspace_due",
                "ix_tasks_workspace_completed", "ix_tasks_project_completed", "ix_tasks_deadline_date");
        List<String> projectIndexes = jdbcTemplate.queryForList(
                "select indexname from pg_indexes where schemaname = 'public' and tablename = 'projects'", String.class);
        assertThat(projectIndexes).contains("ix_projects_workspace_parent_sort");
    }

    @Test
    void tasksRejectBadPriorityDoubleDueAndDurationWithoutTime() {
        UUID owner = insertUser(uniqueEmail());
        UUID project = insertProject(UUID.randomUUID(), insertWorkspace(owner, "PERSONAL"), null, false);
        String insertTask = "insert into tasks (id, workspace_id, project_id, creator_id, content, priority, due_date, "
                + "due_datetime, duration_minutes, sort_order, created_at, updated_at) values (?, "
                + "(select workspace_id from projects where id = ?), ?, ?, 'T', ?, ?, ?, ?, 1024, now(), now())";
        java.sql.Date day = java.sql.Date.valueOf("2026-10-25");
        java.sql.Timestamp nine = java.sql.Timestamp.valueOf("2026-10-25 09:00:00");

        // tham số: id, project (cho subselect workspace_id), project, creator, priority, due_date, due_datetime, duration
        jdbcTemplate.update(insertTask, UUID.randomUUID(), project, project, owner, 4, null, nine, 30);
        assertThatThrownBy(() -> jdbcTemplate.update(insertTask, UUID.randomUUID(), project, project, owner, 5, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(insertTask, UUID.randomUUID(), project, project, owner, 4, day, nine, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(insertTask, UUID.randomUUID(), project, project, owner, 4, day, null, 30))
                .as("duration cần due có giờ").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(insertTask, UUID.randomUUID(), project, project, owner, 4, null, nine, 1441))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void dueDayIsGeneratedFromDateOrDatetime() {
        UUID project = insertProject(UUID.randomUUID(), insertWorkspace(insertUser(uniqueEmail()), "PERSONAL"), null, false);
        UUID withDate = insertTask(project, null, null);
        jdbcTemplate.update("update tasks set due_date = date '2026-10-25' where id = ?", withDate);
        UUID withTime = insertTask(project, null, null);
        jdbcTemplate.update("update tasks set due_datetime = timestamp '2026-10-26 23:30:00' where id = ?", withTime);

        assertThat(jdbcTemplate.queryForObject("select due_day from tasks where id = ?", java.sql.Date.class, withDate))
                .hasToString("2026-10-25");
        assertThat(jdbcTemplate.queryForObject("select due_day from tasks where id = ?", java.sql.Date.class, withTime))
                .hasToString("2026-10-26");
    }

    @Test
    void nonInboxProjectRequiresColor_inboxCannotBeChild_favoritesAcceptOnlyProjectLabelFilter() {
        UUID owner = insertUser(uniqueEmail());
        UUID workspace = insertWorkspace(owner, "PERSONAL");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into projects (id, workspace_id, name, is_inbox, created_at, updated_at) "
                        + "values (?, ?, 'P', false, now(), now())", UUID.randomUUID(), workspace))
                .isInstanceOf(DataIntegrityViolationException.class);
        UUID parent = insertProject(UUID.randomUUID(), workspace, null, false);
        assertThatThrownBy(() -> insertProject(UUID.randomUUID(), workspace, parent, true))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID target = UUID.randomUUID();
        String insertFavorite = "insert into favorites (id, user_id, target_type, target_id, created_at, updated_at) "
                + "values (?, ?, ?, ?, now(), now())";
        jdbcTemplate.update(insertFavorite, UUID.randomUUID(), owner, "PROJECT", target);
        jdbcTemplate.update(insertFavorite, UUID.randomUUID(), owner, "LABEL", target);
        jdbcTemplate.update(insertFavorite, UUID.randomUUID(), owner, "FILTER", target);
        assertThatThrownBy(() -> jdbcTemplate.update(insertFavorite, UUID.randomUUID(), owner, "PROJECT", target))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(insertFavorite, UUID.randomUUID(), owner, "TASK", UUID.randomUUID()))
                .as("bản 3: task không có yêu thích").isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingProjectCascadesToChildProjectsSectionsAndTasks_butKeepsTasksOfOtherProjects() {
        UUID owner = insertUser(uniqueEmail());
        UUID workspace = insertWorkspace(owner, "PERSONAL");
        UUID parent = insertProject(UUID.randomUUID(), workspace, null, false);
        UUID child = insertProject(UUID.randomUUID(), workspace, parent, false);
        UUID section = UUID.randomUUID();
        jdbcTemplate.update("insert into sections (id, project_id, name, sort_order, created_at, updated_at) "
                + "values (?, ?, 'S', 1024, now(), now())", section, child);
        UUID root = insertTask(child, section, null);
        UUID sub = insertTask(child, section, root);
        UUID untouched = insertTask(insertProject(UUID.randomUUID(), workspace, null, false), null, null);

        jdbcTemplate.update("delete from projects where id = ?", parent);

        assertThat(jdbcTemplate.queryForObject("select count(*) from projects where id in (?, ?)", Integer.class,
                parent, child)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from sections where id = ?", Integer.class, section)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from tasks where id in (?, ?)", Integer.class, root, sub))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from tasks where id = ?", Integer.class, untouched)).isOne();
    }

    @Test
    void projectsRejectUnknownColor_andAcceptCalendarViewStyle() {
        UUID workspace = insertWorkspace(insertUser(uniqueEmail()), "PERSONAL");
        assertThatThrownBy(() -> jdbcTemplate.update("insert into projects (id, workspace_id, name, color, is_inbox, "
                + "created_at, updated_at) values (?, ?, 'P', 'pink', false, now(), now())", UUID.randomUUID(), workspace))
                .as("CHECK 20 khoá màu (spec 10.1)").isInstanceOf(DataIntegrityViolationException.class);
        jdbcTemplate.update("insert into projects (id, workspace_id, name, color, view_style, is_inbox, created_at, "
                + "updated_at) values (?, ?, 'P', 'taupe', 'CALENDAR', false, now(), now())", UUID.randomUUID(), workspace);
        assertThatThrownBy(() -> jdbcTemplate.update("insert into projects (id, workspace_id, name, color, view_style, "
                + "is_inbox, created_at, updated_at) values (?, ?, 'P', 'red', 'GRID', false, now(), now())",
                UUID.randomUUID(), workspace)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void tasksHaveWorkspaceId_dueSort_completionBatchId_andCompositeFkKeepsSectionsInsideTheirProject() {
        UUID workspace = insertWorkspace(insertUser(uniqueEmail()), "PERSONAL");
        UUID projectA = insertProject(UUID.randomUUID(), workspace, null, false);
        UUID projectB = insertProject(UUID.randomUUID(), workspace, null, false);
        UUID sectionOfA = UUID.randomUUID();
        jdbcTemplate.update("insert into sections (id, project_id, name, sort_order, created_at, updated_at) "
                + "values (?, ?, 'S', 1024, now(), now())", sectionOfA, projectA);

        // FK ghép (project_id, section_id) → sections(project_id, id): task của B không trỏ được tới section của A (m9)
        assertThatThrownBy(() -> insertTask(projectB, sectionOfA, null)).isInstanceOf(DataIntegrityViolationException.class);
        UUID inSection = insertTask(projectA, sectionOfA, null);
        assertThat(jdbcTemplate.queryForObject("select workspace_id from tasks where id = ?", UUID.class, inSection))
                .isEqualTo(workspace);
        // workspace_id NOT NULL (phi chuẩn, spec 10)
        assertThatThrownBy(() -> jdbcTemplate.update("insert into tasks (id, project_id, content, sort_order, created_at, "
                + "updated_at) values (?, ?, 'T', 1024, now(), now())", UUID.randomUUID(), projectA))
                .isInstanceOf(DataIntegrityViolationException.class);

        // due_sort = COALESCE(due_datetime, due_date::timestamp)
        UUID dated = insertTask(projectA, null, null);
        jdbcTemplate.update("update tasks set due_date = date '2026-10-25' where id = ?", dated);
        UUID timed = insertTask(projectA, null, null);
        jdbcTemplate.update("update tasks set due_datetime = timestamp '2026-10-25 09:30:00' where id = ?", timed);
        String dueSort = "select to_char(due_sort, 'YYYY-MM-DD HH24:MI') from tasks where id = ?";
        assertThat(jdbcTemplate.queryForObject(dueSort, String.class, dated)).isEqualTo("2026-10-25 00:00");
        assertThat(jdbcTemplate.queryForObject(dueSort, String.class, timed)).isEqualTo("2026-10-25 09:30");

        // completion_batch_id (căn cứ Undo, spec 5.1)
        UUID batch = UUID.randomUUID();
        jdbcTemplate.update("update tasks set completed_at = now(), completion_batch_id = ? where id = ?", batch, dated);
        assertThat(jdbcTemplate.queryForObject("select completion_batch_id from tasks where id = ?", UUID.class, dated))
                .isEqualTo(batch);
        // xoá section xoá luôn task của nó (FK ghép ON DELETE CASCADE)
        jdbcTemplate.update("delete from sections where id = ?", sectionOfA);
        assertThat(jdbcTemplate.queryForObject("select count(*) from tasks where id = ?", Integer.class, inSection)).isZero();
    }

    @Test
    void v2BackfillsRefreshTokenFamilyIdWithTheTokenId_forTokensCreatedBeforeV2() {
        // Dựng một schema riêng ở V1, chèn token "cũ", rồi migrate lên V2: family_id phải = id (spec 10, I8).
        try {
            javax.sql.DataSource dataSource = jdbcTemplate.getDataSource();
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas("v2_backfill").defaultSchema("v2_backfill")
                    .target("1").load().migrate();
            UUID user = UUID.randomUUID();
            UUID token = UUID.randomUUID();
            jdbcTemplate.update("insert into v2_backfill.users (id, email, password_hash, display_name, created_at, "
                    + "updated_at) values (?, ?, 'h', 'U', now(), now())", user, uniqueEmail());
            jdbcTemplate.update("insert into v2_backfill.refresh_tokens (id, user_id, token_hash, expires_at, created_at, "
                    + "updated_at) values (?, ?, ?, now() + interval '1 day', now(), now())", token, user, "hash-" + token);

            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas("v2_backfill").defaultSchema("v2_backfill")
                    .load().migrate(); // lên V2

            assertThat(jdbcTemplate.queryForObject("select family_id from v2_backfill.refresh_tokens where id = ?",
                    UUID.class, token)).isEqualTo(token);
        } finally {
            jdbcTemplate.execute("drop schema if exists v2_backfill cascade");
        }
    }
}
