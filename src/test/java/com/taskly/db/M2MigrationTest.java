package com.taskly.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.support.M2ApiTestBase;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.RowCallbackHandler;

/** Kiểm trực tiếp bằng SQL những gì V3__m2_search_labels_filters.sql tạo (spec mục 10, 5.7, 5.10, 13). */
class M2MigrationTest extends M2ApiTestBase {

    private List<String> viewKeys(UUID userId) {
        return jdbcTemplate.queryForList("select view_key from view_options where user_id = ? order by view_key",
                String.class, userId);
    }

    @Test
    void flywayAppliedVersion3() {
        Boolean success = jdbcTemplate.queryForObject(
                "select success from flyway_schema_history where version = '3'", Boolean.class);
        assertThat(success).isTrue();
    }

    @Test
    void enablesUnaccentAndTrigramExtensions() {
        assertThat(jdbcTemplate.queryForList("select extname from pg_extension", String.class))
                .contains("unaccent", "pg_trgm");
    }

    @Test
    void fUnaccentIsImmutableAndFoldsVietnamese() {
        assertThat(jdbcTemplate.queryForObject("select provolatile from pg_proc where proname = 'f_unaccent'",
                String.class)).isEqualTo("i");
        assertThat(jdbcTemplate.queryForObject("select f_unaccent(lower('Họp ĐẠI hội Đồng đều'))", String.class))
                .isEqualTo("hop dai hoi dong deu");
        assertThat(jdbcTemplate.queryForObject("select f_unaccent(lower(?))", String.class, "ƯỚC MƠ Ở ĐÂY"))
                .isEqualTo("uoc mo o day");
        assertThat(jdbcTemplate.queryForObject("select f_unaccent(null)", String.class)).isNull();
    }

    @Test
    void createsTrigramIndexesOnNormalizedNames() {
        Map<String, String> definitions = new HashMap<>();
        jdbcTemplate.query("select indexname, indexdef from pg_indexes where indexname like 'ix\\_%\\_trgm'",
                (RowCallbackHandler) rs -> definitions.put(rs.getString("indexname"), rs.getString("indexdef")));

        assertThat(definitions).containsOnlyKeys("ix_tasks_content_trgm", "ix_tasks_description_trgm",
                "ix_projects_name_trgm", "ix_sections_name_trgm", "ix_labels_name_trgm", "ix_filters_name_trgm",
                // M8b (V12): tìm comment dùng cùng biểu thức
                "ix_comments_content_trgm");
        assertThat(definitions.values())
                .allSatisfy(def -> assertThat(def).contains("USING gin", "f_unaccent(lower(", "gin_trgm_ops"));
    }

    /** Tắt seq scan + index scan thường → nếu biểu thức khớp chỉ mục, planner phải chọn bitmap scan trigram. */
    @Test
    void plannerCanUseContentTrigramIndexForIlike() {
        String plan = jdbcTemplate.execute((ConnectionCallback<String>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET enable_seqscan = off");
                statement.execute("SET enable_indexscan = off");
                statement.execute("SET enable_indexonlyscan = off");
                try (ResultSet rs = statement.executeQuery(
                        "EXPLAIN SELECT id FROM tasks WHERE f_unaccent(lower(content)) ILIKE '%hop%'")) {
                    StringBuilder text = new StringBuilder();
                    while (rs.next()) {
                        text.append(rs.getString(1)).append('\n');
                    }
                    return text.toString();
                } finally {
                    statement.execute("RESET enable_seqscan");
                    statement.execute("RESET enable_indexscan");
                    statement.execute("RESET enable_indexonlyscan");
                }
            }
        });
        assertThat(plan).contains("ix_tasks_content_trgm");
    }

    @Test
    void labelNameCheck_rejectsWhitespaceSpecialCharactersAndLength() throws Exception {
        UUID user = registerUser(uniqueEmail()).id();
        data.label(user, "gấp");
        data.label(user, "x".repeat(60));

        for (String bad : List.of("hai từ", "a@b", "a%b", "a#b", "a/b", "a,b", "a(b", "a)b", "a|b", "a&b", "a!b",
                "a\\b", "a*b", "x".repeat(61), "")) {
            assertThatThrownBy(() -> data.label(user, bad)).as(bad)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void labelName_isUniqueIgnoringCasePerUser() throws Exception {
        UUID alice = registerUser(uniqueEmail()).id();
        UUID bob = registerUser(uniqueEmail()).id();
        data.label(alice, "Gấp");

        assertThatThrownBy(() -> data.label(alice, "gẤP")).isInstanceOf(DataIntegrityViolationException.class);
        data.label(bob, "gấp");
    }

    @Test
    void taskLabels_cascadeOnTaskAndLabelDelete() throws Exception {
        UUID user = registerUser(uniqueEmail()).id();
        UUID inbox = data.inboxOf(user);
        UUID first = data.task(inbox, "Một");
        UUID second = data.task(inbox, "Hai");
        UUID home = data.label(user, "nha");
        UUID urgent = data.label(user, "gap");
        data.tag(first, home, urgent);
        data.tag(second, home);

        data.deleteTask(first);
        assertThat(jdbcTemplate.queryForObject("select count(*) from task_labels where task_id = ?", Integer.class,
                first)).isZero();

        jdbcTemplate.update("delete from labels where id = ?", home);
        assertThat(jdbcTemplate.queryForObject("select count(*) from task_labels where task_id = ?", Integer.class,
                second)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from task_labels where label_id = ?", Integer.class,
                urgent)).isZero();
    }

    @Test
    void viewOptions_filterLabelFkSetsNull_butPolymorphicKeysAreNotDeletedByTheDatabase() throws Exception {
        UUID user = registerUser(uniqueEmail()).id();
        UUID label = data.label(user, "nha");
        UUID filter = data.filter(user, "Hôm nay", "today");
        UUID parent = data.project(user, "Cha");
        UUID child = data.project(user, parent, "Con");
        data.viewOption(user, "label:" + label, null);
        data.viewOption(user, "filter:" + filter, null);
        data.viewOption(user, "project:" + parent, null);
        data.viewOption(user, "project:" + child, null);
        data.viewOption(user, "TODAY", label);

        jdbcTemplate.update("delete from labels where id = ?", label);
        jdbcTemplate.update("delete from filters where id = ?", filter);
        jdbcTemplate.update("delete from projects where id = ?", parent); // dự án con bị cascade

        // FK ON DELETE SET NULL: DB tự bỏ tham chiếu label ở Display của view khác ...
        assertThat(jdbcTemplate.queryForObject(
                "select filter_label_id from view_options where user_id = ? and view_key = 'TODAY'",
                UUID.class, user)).isNull();
        // ... nhưng khoá view_key đa hình (không có FK) DB KHÔNG tự xoá: ViewOptionsService lo (spec 8.2 quy tắc 8,
        // Task 17 — nghe ProjectsDeletedEvent / LabelDeletedEvent / FilterDeletedEvent; test API ở ViewOptionsApiTest).
        assertThat(viewKeys(user)).containsExactlyInAnyOrder("label:" + label, "filter:" + filter,
                "project:" + parent, "project:" + child, "TODAY");
    }

    /** Spec 8.2 quy tắc 8: dọn view_options bằng service, không bằng trigger / hàm DB. */
    @Test
    void noTriggerOrFunctionRemovesViewOptions() {
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from pg_trigger where not tgisinternal and tgname like '%view_options%'",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from pg_proc where proname = 'f_drop_view_options'",
                Integer.class)).isZero();
    }

    @Test
    void viewOptions_checkConstraintsRejectBadValues() throws Exception {
        UUID user = registerUser(uniqueEmail()).id();
        String insert = "insert into view_options (id, user_id, view_key, sort_by, sort_direction, group_by, "
                + "filter_priority, filter_due, show_completed, created_at, updated_at) "
                + "values (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, false, now(), now())";

        assertThatThrownBy(() -> jdbcTemplate.update(insert, user, "today", "DATE", "ASC", "NONE", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(insert, user, "TODAY", "FOO", "ASC", "NONE", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(insert, user, "TODAY", "DATE", "UP", "NONE", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(insert, user, "TODAY", "DATE", "ASC", "SECTION", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(insert, user, "TODAY", "DATE", "ASC", "NONE", 5, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(insert, user, "TODAY", "DATE", "ASC", "NONE", null, "SOON"))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbcTemplate.update(insert, user, "UPCOMING", "PRIORITY", "DESC", "NONE", 1, "NEXT_7_DAYS");
        assertThatThrownBy(() -> jdbcTemplate.update(insert, user, "UPCOMING", "DATE", "ASC", "NONE", null, null))
                .as("unique (user_id, view_key)").isInstanceOf(DataIntegrityViolationException.class);
    }
}
