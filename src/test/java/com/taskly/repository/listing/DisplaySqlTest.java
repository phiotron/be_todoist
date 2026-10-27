package com.taskly.repository.listing;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.entity.DueFilter;
import com.taskly.entity.GroupBy;
import com.taskly.entity.SortBy;
import com.taskly.entity.SortDirection;
import com.taskly.repository.listing.DisplaySql.DueRange;
import com.taskly.service.display.DisplayOptions;
import com.taskly.service.display.ViewKind;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

class DisplaySqlTest {

    private static DisplayOptions options(SortBy sort, SortDirection direction, GroupBy group) {
        return new DisplayOptions(sort, direction, group, null, null, null);
    }

    private static List<String> expressions(List<SortKey> keys) {
        return keys.stream().map(SortKey::expression).toList();
    }

    @Test
    void labelViewDefaults_nullFlagThenDueKeyThenId() {
        List<SortKey> keys = DisplaySql.keys(ViewKind.LABEL, options(SortBy.DATE, SortDirection.ASC, GroupBy.NONE));

        assertThat(keys).hasSize(3);
        assertThat(keys.get(0).descending()).isFalse();
        assertThat(keys.get(1).kind()).isEqualTo(KeyKind.LOCAL_DATETIME);
        assertThat(keys.get(2).expression()).isEqualTo("t.id");
    }

    /** Khoá ngày-giờ = cột sinh due_sort của V2 (cùng khoá với view M1), không tự tính lại từ due_date / due_datetime. */
    @Test
    void dueKeyIsTheGeneratedDueSortColumn() {
        List<SortKey> keys = DisplaySql.keys(ViewKind.LABEL, options(SortBy.DATE, SortDirection.ASC, GroupBy.NONE));

        assertThat(keys.get(1).expression()).isEqualTo("COALESCE(t.due_sort, TIMESTAMP '9999-12-31 00:00:00')")
                .doesNotContain("due_datetime", "due_date");
    }

    @Test
    void descendingFlipsTheSortKeyButKeepsNullFlagAscending() {
        List<SortKey> keys = DisplaySql.keys(ViewKind.FILTER, options(SortBy.DATE, SortDirection.DESC, GroupBy.NONE));

        assertThat(keys.get(0).descending()).isFalse();   // cờ null: không có ngày luôn cuối
        assertThat(keys.get(1).descending()).isTrue();    // khoá ngày theo order
        assertThat(keys.get(2).descending()).isFalse();   // id
    }

    @Test
    void groupKeysComeBeforeSortKeys_andAreAlwaysAscending() {
        List<SortKey> keys = DisplaySql.keys(ViewKind.FILTER, options(SortBy.NAME, SortDirection.DESC, GroupBy.PRIORITY));

        assertThat(expressions(keys)).containsExactly("t.priority", "f_unaccent(lower(t.content))", "t.id");
        assertThat(keys.get(0).descending()).isFalse();
        assertThat(keys.get(1).descending()).isTrue();
    }

    @Test
    void projectGroup_usesPathThenProjectId_labelGroup_usesFirstLabel() {
        assertThat(DisplaySql.keys(ViewKind.LABEL, options(SortBy.NAME, SortDirection.ASC, GroupBy.PROJECT)))
                .extracting(SortKey::kind)
                .containsExactly(KeyKind.LONG_ARRAY, KeyKind.ID, KeyKind.TEXT, KeyKind.ID);
        assertThat(DisplaySql.keys(ViewKind.LABEL, options(SortBy.NAME, SortDirection.ASC, GroupBy.LABEL)))
                .extracting(SortKey::kind)
                .containsExactly(KeyKind.LONG, KeyKind.ID, KeyKind.TEXT, KeyKind.ID);
    }

    @Test
    void todayDateSort_putsOverdueFlagFirstAndM1TieBreakers() {
        List<SortKey> keys = DisplaySql.keys(ViewKind.TODAY, options(SortBy.DATE, SortDirection.ASC, GroupBy.NONE));

        assertThat(keys.get(0).expression()).isEqualTo("CASE WHEN t.due_day < :today THEN 0 ELSE 1 END");
        assertThat(keys).extracting(SortKey::kind).containsExactly(KeyKind.INT, KeyKind.INT, KeyKind.LOCAL_DATETIME,
                KeyKind.INT, KeyKind.LONG_ARRAY, KeyKind.LONG, KeyKind.ID);
    }

    /** Spec 3.1: khối Quá hạn luôn đứng đầu Today — order=desc chỉ đảo khoá ngày-giờ bên trong từng khối. */
    @Test
    void todayDateSortDesc_keepsOverdueFlagAscending_butFlipsTheDateTimeKey() {
        List<SortKey> keys = DisplaySql.keys(ViewKind.TODAY, options(SortBy.DATE, SortDirection.DESC, GroupBy.NONE));

        assertThat(keys.get(0).expression()).isEqualTo("CASE WHEN t.due_day < :today THEN 0 ELSE 1 END");
        assertThat(keys.get(0).descending()).isFalse();   // cờ khối Quá hạn: KHÔNG đảo theo order
        assertThat(keys.get(1).descending()).isFalse();   // cờ null
        assertThat(keys.get(2).kind()).isEqualTo(KeyKind.LOCAL_DATETIME);
        assertThat(keys.get(2).descending()).isTrue();    // khoá ngày-giờ theo order
    }

    @Test
    void overdueFlagOnlyForTodayWithDateSort() {
        assertThat(DisplaySql.keys(ViewKind.TODAY, options(SortBy.PRIORITY, SortDirection.ASC, GroupBy.NONE)))
                .extracting(SortKey::expression).noneMatch(e -> e.contains(":today"));
        assertThat(DisplaySql.keys(ViewKind.UPCOMING, options(SortBy.DATE, SortDirection.ASC, GroupBy.NONE)))
                .extracting(SortKey::expression).noneMatch(e -> e.contains(":today"));
        assertThat(DisplaySql.keys(ViewKind.LABEL, options(SortBy.DATE, SortDirection.ASC, GroupBy.NONE)))
                .extracting(SortKey::expression).noneMatch(e -> e.contains(":today"));
    }

    /** Spec 5.1, 18.1 C3: khối Quá hạn của Today = due_day < hôm nay; filterDue=overdue = isOverdue (giờ đã qua). */
    @Test
    void overdueFilterUsesIsOverdue_whileTheTodayBlockUsesDueDay() {
        StringBuilder where = new StringBuilder();
        DisplaySql.appendFilters(new DisplayOptions(SortBy.DATE, SortDirection.ASC, GroupBy.NONE, null, null,
                DueFilter.OVERDUE), LocalDate.of(2026, 11, 4), where, new MapSqlParameterSource());

        assertThat(where.toString()).contains("t.due_datetime < :now", "t.due_date < :today");
        assertThat(DisplaySql.IS_OVERDUE).isEqualTo("(t.due_datetime < :now OR t.due_date < :today)");
        assertThat(DisplaySql.OVERDUE_BLOCK).isEqualTo("t.due_day < :today").doesNotContain(":now");
    }

    @Test
    void upcomingAlwaysGroupsByDayFirst() {
        List<SortKey> keys = DisplaySql.keys(ViewKind.UPCOMING, options(SortBy.NAME, SortDirection.ASC, GroupBy.NONE));

        assertThat(keys.get(0).expression()).isEqualTo("t.due_day");
        assertThat(keys.get(0).kind()).isEqualTo(KeyKind.DATE);
    }

    @Test
    void projectViewWithoutGroupFollowsSectionOrder() {
        List<SortKey> keys = DisplaySql.keys(ViewKind.PROJECT, options(SortBy.MANUAL, SortDirection.ASC, GroupBy.NONE));

        assertThat(keys).extracting(SortKey::kind)
                .containsExactly(KeyKind.INT, KeyKind.LONG, KeyKind.ID, KeyKind.LONG, KeyKind.ID);
        assertThat(keys.get(3).expression()).isEqualTo("t.sort_order");
    }

    @Test
    void dueRanges_followTheAccountToday() {
        LocalDate wednesday = LocalDate.of(2026, 11, 4);

        assertThat(DisplaySql.range(DueFilter.TODAY, wednesday)).isEqualTo(new DueRange(wednesday, wednesday));
        assertThat(DisplaySql.range(DueFilter.THIS_WEEK, wednesday))
                .isEqualTo(new DueRange(wednesday, LocalDate.of(2026, 11, 8)));
        assertThat(DisplaySql.range(DueFilter.THIS_WEEK, LocalDate.of(2026, 11, 8)))
                .isEqualTo(new DueRange(LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 8)));
        assertThat(DisplaySql.range(DueFilter.NEXT_7_DAYS, wednesday))
                .isEqualTo(new DueRange(wednesday, LocalDate.of(2026, 11, 10)));
        assertThat(DisplaySql.range(DueFilter.THIS_MONTH, wednesday))
                .isEqualTo(new DueRange(wednesday, LocalDate.of(2026, 11, 30)));
        assertThat(DisplaySql.range(DueFilter.NEXT_30_DAYS, wednesday))
                .isEqualTo(new DueRange(wednesday, LocalDate.of(2026, 12, 3)));
    }
}
