package com.taskly.repository.listing;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.repository.listing.FilterSqlTranslator.Context;
import com.taskly.repository.listing.FilterSqlTranslator.Sql;
import com.taskly.service.filterquery.DateOrder;
import com.taskly.service.filterquery.FilterQueryParser;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class FilterSqlTranslatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 4, 10, 0);
    private static final Context CONTEXT = new Context(TODAY, NOW, "Asia/Ho_Chi_Minh");

    private static Sql sql(String query) {
        return FilterSqlTranslator.translate(FilterQueryParser.parse(query, DateOrder.DMY), CONTEXT);
    }

    @Test
    void priorityAndNegatedLabel_useParametersOnly() {
        Sql sql = sql("p1 & !@home");

        assertThat(sql.condition()).isEqualTo("(COALESCE(t.priority = :fq0, false) AND (NOT COALESCE(EXISTS ("
                + "SELECT 1 FROM task_labels ftl JOIN labels fl ON fl.id = ftl.label_id WHERE ftl.task_id = t.id "
                + "AND fl.user_id = :userId AND lower(fl.name) LIKE lower(:fq1) ESCAPE '\\'), false)))");
        assertThat(sql.params()).containsEntry("fq0", 1).containsEntry("fq1", "home")
                .containsEntry("fqToday", TODAY).containsEntry("fqNow", NOW)
                .containsEntry("fqTz", "Asia/Ho_Chi_Minh");
    }

    @Test
    void relativeAndDayMonthDates_resolveAgainstToday() {
        assertThat(sql("tomorrow").condition()).isEqualTo("COALESCE(t.due_day = :fq0, false)");
        assertThat(sql("tomorrow").params()).containsEntry("fq0", LocalDate.of(2026, 11, 5));
        assertThat(sql("date before: 29/02").condition()).isEqualTo("COALESCE(t.due_day < :fq0, false)");
        assertThat(sql("date before: 29/02").params()).containsEntry("fq0", LocalDate.of(2026, 2, 28));
        assertThat(sql("deadline after: 2026-12-01").condition()).isEqualTo("COALESCE(t.deadline_date > :fq0, false)");
    }

    @Test
    void overdueAndNextDays_useFloatingNowAndHalfOpenRange() {
        assertThat(sql("overdue").condition())
                .isEqualTo("COALESCE((t.due_datetime < :fqNow OR t.due_date < :fqToday), false)");
        assertThat(sql("next 3 days").condition())
                .isEqualTo("COALESCE((t.due_day >= :fqToday AND t.due_day < :fq0), false)");
        assertThat(sql("next 3 days").params()).containsEntry("fq0", LocalDate.of(2026, 11, 7));
    }

    @Test
    void createdUsesAccountTimeZone() {
        assertThat(sql("created: today").condition())
                .isEqualTo("COALESCE(CAST(t.created_at AT TIME ZONE :fqTz AS date) = :fq0, false)");
    }

    @Test
    void dateTimeLiterals_compareFloatingMinutes() {
        assertThat(sql("date: 2026-11-05T09:30").condition())
                .isEqualTo("COALESCE((t.due_datetime >= :fq0 AND t.due_datetime < :fq1), false)");
        assertThat(sql("date: 2026-11-05T09:30").params())
                .containsEntry("fq0", LocalDateTime.of(2026, 11, 5, 9, 30))
                .containsEntry("fq1", LocalDateTime.of(2026, 11, 5, 9, 31));
        assertThat(sql("date before: 2026-11-05T09:30").condition())
                .isEqualTo("COALESCE((t.due_datetime < :fq0 OR t.due_date < :fq1), false)");
        assertThat(sql("date before: 2026-11-05T09:30").params())
                .containsEntry("fq0", LocalDateTime.of(2026, 11, 5, 9, 30))
                .containsEntry("fq1", LocalDate.of(2026, 11, 5));
        assertThat(sql("date after: 2026-11-05T09:30").condition())
                .isEqualTo("COALESCE((t.due_datetime >= :fq0 OR t.due_date > :fq1), false)");
        assertThat(sql("date after: 2026-11-05T09:30").params())
                .containsEntry("fq0", LocalDateTime.of(2026, 11, 5, 9, 31))
                .containsEntry("fq1", LocalDate.of(2026, 11, 5));
        assertThat(sql("created: 2026-10-01T10:00").condition()).isEqualTo("COALESCE(((t.created_at AT TIME ZONE :fqTz) "
                + ">= :fq0 AND (t.created_at AT TIME ZONE :fqTz) < :fq1), false)");
        assertThat(sql("created before: 2026-10-01T10:00").condition())
                .isEqualTo("COALESCE((t.created_at AT TIME ZONE :fqTz) < :fq0, false)");
        assertThat(sql("created after: 2026-10-01T10:00").condition())
                .isEqualTo("COALESCE((t.created_at AT TIME ZONE :fqTz) >= :fq0, false)");
    }

    @Test
    void projectWithChildren_usesRecursiveTreeInScope() {
        Sql sql = sql("##Work*");

        assertThat(sql.condition()).contains("WITH RECURSIVE fpt(id)", "fp.workspace_id IN (:workspaceIds)",
                "UNION SELECT fc.id FROM projects fc JOIN fpt ON fc.parent_id = fpt.id");
        assertThat(sql.params()).containsEntry("fq0", "Work%");
        assertThat(sql("#Work").condition()).doesNotContain("RECURSIVE");
    }

    @Test
    void searchText_isAccentInsensitiveSubstring() {
        Sql sql = sql("search: báo cáo");

        assertThat(sql.condition()).contains("f_unaccent(lower(t.content)) ILIKE f_unaccent(lower(:fq0))",
                "f_unaccent(lower(t.description)) ILIKE f_unaccent(lower(:fq0))");
        assertThat(sql.params()).containsEntry("fq0", "%báo cáo%");
    }

    @Test
    void negatedSection_isNullSafe_andSimpleAtoms() {
        assertThat(sql("!/Work").condition()).startsWith("(NOT COALESCE(t.section_id IN (");
        assertThat(sql("!/*").condition()).isEqualTo("(NOT COALESCE(t.section_id IS NOT NULL, false))");
        assertThat(sql("no date | no time").condition())
                .isEqualTo("(COALESCE(t.due_day IS NULL, false) OR COALESCE(t.due_date IS NOT NULL, false))");
        assertThat(sql("subtask & recurring").condition()).isEqualTo(
                "(COALESCE(t.parent_id IS NOT NULL, false) AND COALESCE(t.recurrence_rule IS NOT NULL, false))");
        assertThat(sql("no labels").condition())
                .isEqualTo("COALESCE(NOT EXISTS (SELECT 1 FROM task_labels ftl WHERE ftl.task_id = t.id), false)");
        assertThat(sql("no deadline").condition()).isEqualTo("COALESCE(t.deadline_date IS NULL, false)");
    }

    @Test
    void aliasesAndDeadlineOn_translateLikeTheirCanonicalForms() {
        assertThat(sql("od").condition()).isEqualTo(sql("overdue").condition());
        assertThat(sql("quá hạn").condition()).isEqualTo(sql("overdue").condition());
        assertThat(sql("next 1 days").params()).containsEntry("fq0", LocalDate.of(2026, 11, 5));
        assertThat(sql("deadline: today").condition()).isEqualTo("COALESCE(t.deadline_date = :fq0, false)");
        assertThat(sql("!(today | overdue)").condition()).isEqualTo("(NOT (COALESCE(t.due_day = :fq0, false) OR "
                + "COALESCE((t.due_datetime < :fqNow OR t.due_date < :fqToday), false)))");
    }

    @Test
    void userText_neverReachesTheSqlString() {
        Sql search = sql("search: a'b; DROP TABLE tasks;--");
        Sql label = sql("@x';DROP");

        assertThat(search.condition()).doesNotContain("DROP", "a'b");
        assertThat(search.params()).containsEntry("fq0", "%a'b; DROP TABLE tasks;--%");
        assertThat(label.condition()).doesNotContain("DROP", "x'");
        assertThat(label.params()).containsEntry("fq0", "x';DROP");
    }

    @Test
    void plainProjectAndWildcardLabel_bindPatternAndScopeByWorkspace() {
        Sql project = sql("#Work");
        assertThat(project.condition()).isEqualTo("COALESCE(t.project_id IN (SELECT fp.id FROM projects fp "
                + "WHERE fp.workspace_id IN (:workspaceIds) AND lower(fp.name) LIKE lower(:fq0) ESCAPE '\\'), false)");
        assertThat(sql("@home*").params()).containsEntry("fq0", "home%");
    }
}
