package com.taskly.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.IntegrationTestBase;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** V22__task_review.sql: cột chờ duyệt của tasks, CHECK, chỉ mục một phần, loại thông báo / sự kiện nhật ký mới. */
class TaskReviewMigrationTest extends IntegrationTestBase {

    @Test
    void flywayAppliedVersion22_andIndexExists() {
        assertThat(jdbcTemplate.queryForObject("select success from flyway_schema_history where version = '22'",
                Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForObject("select indexdef from pg_indexes where indexname = 'ix_tasks_review_pending'",
                String.class)).contains("project_id").contains("PENDING");
    }

    @Test
    void newTasksDefaultToNone_checkRejectsOtherValues() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID task = testData.task(user.id(), testData.inboxOf(user.id()), "x");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select review_status, review_requested_by, review_requested_at from tasks where id = ?", task);
        assertThat(row.get("review_status")).isEqualTo("NONE");
        assertThat(row.get("review_requested_by")).isNull();
        assertThat(row.get("review_requested_at")).isNull();
        jdbcTemplate.update("update tasks set review_status = 'PENDING', review_requested_by = ?, "
                + "review_requested_at = now() where id = ?", user.id(), task);
        assertThatThrownBy(() -> jdbcTemplate.update("update tasks set review_status = 'DONE' where id = ?", task))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void v22AppliesOnExistingData_rowsBecomeNone() {
        String schema = "v22_review";
        try {
            DataSource dataSource = jdbcTemplate.getDataSource();
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("21").load().migrate();
            UUID user = UUID.randomUUID();
            UUID workspace = UUID.randomUUID();
            UUID project = UUID.randomUUID();
            UUID task = UUID.randomUUID();
            jdbcTemplate.update("insert into v22_review.users (id, email, password_hash, display_name, created_at, "
                    + "updated_at) values (?, ?, 'h', 'U', now(), now())", user, uniqueEmail());
            jdbcTemplate.update("insert into v22_review.workspaces (id, name, type, owner_id, created_at, updated_at) "
                    + "values (?, 'W', 'PERSONAL', ?, now(), now())", workspace, user);
            jdbcTemplate.update("insert into v22_review.projects (id, workspace_id, name, color, is_inbox, created_at, "
                    + "updated_at) values (?, ?, 'P', 'charcoal', false, now(), now())", project, workspace);
            jdbcTemplate.update("insert into v22_review.tasks (id, workspace_id, project_id, creator_id, content, "
                    + "priority, sort_order, created_at, updated_at) values (?, ?, ?, ?, 'cũ', 4, 1, now(), now())",
                    task, workspace, project, user);

            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate(); // lên V22

            Map<String, Object> row = jdbcTemplate.queryForMap("select review_status, review_requested_by, "
                    + "review_requested_at, completed_at from v22_review.tasks where id = ?", task);
            assertThat(row.get("review_status")).isEqualTo("NONE");
            assertThat(row.get("review_requested_by")).isNull();
            assertThat(row.get("review_requested_at")).isNull();
            assertThat(row.get("completed_at")).isNull();
        } finally {
            jdbcTemplate.execute("drop schema if exists " + schema + " cascade");
        }
    }

    @Test
    void reviewRequestedByForeignKey_setsNullOnUserDelete() {
        assertThat(jdbcTemplate.queryForObject("select count(*) from pg_constraint c join pg_attribute a "
                + "on a.attrelid = c.conrelid and a.attnum = any (c.conkey) where c.conrelid = 'tasks'::regclass "
                + "and c.contype = 'f' and a.attname = 'review_requested_by' and c.confdeltype = 'n'",
                Integer.class)).isEqualTo(1);
    }
}
