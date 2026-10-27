package com.taskly.service.display;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.common.error.ApiException;
import com.taskly.entity.DueFilter;
import com.taskly.entity.GroupBy;
import com.taskly.entity.SortBy;
import com.taskly.entity.SortDirection;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DisplayParamsTest {

    private static DisplayQuery query(String sort, String order, String group) {
        return new DisplayQuery(sort, order, group, null, null, null);
    }

    private static DisplayQuery due(String filterDue) {
        return new DisplayQuery(null, null, null, null, null, filterDue);
    }

    private static void assertInvalid(ViewKind kind, DisplayQuery query) {
        assertThatThrownBy(() -> DisplayParams.fromQuery(kind, query))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(400);
                    assertThat(ex.getBody().getDetail()).isEqualTo(DisplayTexts.DISPLAY_INVALID);
                });
    }

    @Test
    void noDisplayParameter_isEmpty() {
        assertThat(DisplayParams.fromQuery(ViewKind.TODAY, new DisplayQuery(null, null, null, null, null, null)))
                .isEmpty();
        assertThat(DisplayParams.fromQuery(ViewKind.TODAY, null)).isEmpty();
    }

    @Test
    void missingParts_takeDefaultsOfTheView() {
        assertThat(DisplayParams.fromQuery(ViewKind.TODAY, query("priority", null, null)).orElseThrow())
                .isEqualTo(new DisplayOptions(SortBy.PRIORITY, SortDirection.ASC, GroupBy.NONE, null, null, null));
        assertThat(DisplayParams.fromQuery(ViewKind.PROJECT, query(null, "desc", null)).orElseThrow())
                .isEqualTo(new DisplayOptions(SortBy.MANUAL, SortDirection.DESC, GroupBy.NONE, null, null, null));
    }

    @Test
    void parsesEveryParameter() {
        UUID label = UUID.randomUUID();
        DisplayOptions options = DisplayParams.fromQuery(ViewKind.FILTER,
                new DisplayQuery("name", "desc", "project", "2", label.toString(), "this_week")).orElseThrow();

        assertThat(options).isEqualTo(new DisplayOptions(SortBy.NAME, SortDirection.DESC, GroupBy.PROJECT, 2, label,
                DueFilter.THIS_WEEK));
    }

    @Test
    void defaults_followSpecTable() {
        assertThat(DisplayRules.defaults(ViewKind.TODAY).sortBy()).isEqualTo(SortBy.DATE);
        assertThat(DisplayRules.defaults(ViewKind.UPCOMING).sortBy()).isEqualTo(SortBy.DATE);
        assertThat(DisplayRules.defaults(ViewKind.PROJECT).sortBy()).isEqualTo(SortBy.MANUAL);
        assertThat(DisplayRules.defaults(ViewKind.LABEL).sortBy()).isEqualTo(SortBy.DATE);
        assertThat(DisplayRules.defaults(ViewKind.FILTER).groupBy()).isEqualTo(GroupBy.NONE);
        for (ViewKind kind : ViewKind.values()) {
            assertThat(DisplayRules.defaults(kind).direction()).isEqualTo(SortDirection.ASC);
            assertThat(DisplayRules.defaults(kind).groupBy()).isEqualTo(GroupBy.NONE);
            assertThat(DisplayRules.defaults(kind).filterDue()).isNull();
        }
        assertThat(DisplayRules.allowsShowCompleted(ViewKind.PROJECT)).isTrue();
        assertThat(DisplayRules.allowsShowCompleted(ViewKind.TODAY)).isFalse();
        assertThat(DisplayRules.allowsShowCompleted(ViewKind.UPCOMING)).isFalse();
        assertThat(DisplayRules.allowsShowCompleted(ViewKind.LABEL)).isFalse();
        assertThat(DisplayRules.allowsShowCompleted(ViewKind.FILTER)).isFalse();
    }

    @Test
    void sortNotAllowedForView_isRejected() {
        assertInvalid(ViewKind.TODAY, query("manual", null, null));
        assertInvalid(ViewKind.UPCOMING, query("manual", null, null));
        assertInvalid(ViewKind.LABEL, query("manual", null, null));
        assertInvalid(ViewKind.FILTER, query("manual", null, null));
    }

    @Test
    void sortAllowedSets_matchSpecTableForEveryView() {
        String[] dated = {"date", "priority", "name", "added", "deadline"};
        for (ViewKind kind : new ViewKind[] {ViewKind.TODAY, ViewKind.UPCOMING, ViewKind.LABEL, ViewKind.FILTER}) {
            for (String sort : dated) {
                assertThat(DisplayParams.fromQuery(kind, query(sort, null, null))).as(kind + " " + sort).isPresent();
            }
        }
        for (String sort : new String[] {"manual", "date", "deadline", "priority", "name", "added"}) {
            assertThat(DisplayParams.fromQuery(ViewKind.PROJECT, query(sort, null, null))).as("PROJECT " + sort)
                    .isPresent();
        }
    }

    @Test
    void groupNotAllowedForView_isRejected() {
        assertInvalid(ViewKind.TODAY, query(null, null, "date"));
        assertInvalid(ViewKind.UPCOMING, query(null, null, "priority"));
        assertInvalid(ViewKind.PROJECT, query(null, null, "project"));
        assertThat(DisplayParams.fromQuery(ViewKind.LABEL, query(null, null, "label"))).isPresent();
        assertThat(DisplayParams.fromQuery(ViewKind.PROJECT, query(null, null, "label"))).isPresent();
    }

    @Test
    void groupAllowedSets_matchSpecTableForEveryView() {
        for (String group : new String[] {"none", "priority", "project", "label", "deadline"}) {
            assertThat(DisplayParams.fromQuery(ViewKind.TODAY, query(null, null, group))).as("TODAY " + group)
                    .isPresent();
        }
        assertThat(DisplayParams.fromQuery(ViewKind.UPCOMING, query(null, null, "none"))).isPresent();
        for (String group : new String[] {"none", "date", "deadline", "priority", "label"}) {
            assertThat(DisplayParams.fromQuery(ViewKind.PROJECT, query(null, null, group))).as("PROJECT " + group)
                    .isPresent();
        }
        for (ViewKind kind : new ViewKind[] {ViewKind.LABEL, ViewKind.FILTER}) {
            for (String group : new String[] {"none", "date", "deadline", "priority", "project", "label"}) {
                assertThat(DisplayParams.fromQuery(kind, query(null, null, group))).as(kind + " " + group)
                        .isPresent();
            }
        }
        assertInvalid(ViewKind.TODAY, query(null, null, "date"));
        for (String group : new String[] {"date", "deadline", "priority", "project", "label"}) {
            assertInvalid(ViewKind.UPCOMING, query(null, null, group));
        }
    }

    @Test
    void filterDueNoDateOrOverdue_isRejectedWhereTheResultIsAlwaysEmpty() {
        assertInvalid(ViewKind.TODAY, due("no_date"));
        assertInvalid(ViewKind.UPCOMING, due("no_date"));
        assertInvalid(ViewKind.UPCOMING, due("overdue"));
        assertThat(DisplayParams.fromQuery(ViewKind.TODAY, due("overdue"))).isPresent();
        assertThat(DisplayParams.fromQuery(ViewKind.UPCOMING, due("next_7_days"))).isPresent();
        for (ViewKind kind : new ViewKind[] {ViewKind.PROJECT, ViewKind.LABEL, ViewKind.FILTER}) {
            assertThat(DisplayParams.fromQuery(kind, due("no_date"))).isPresent();
            assertThat(DisplayParams.fromQuery(kind, due("overdue"))).isPresent();
        }
    }

    @Test
    void filterDue_projectLabelFilterAcceptAllSevenValues() {
        String[] all = {"today", "this_week", "next_7_days", "this_month", "next_30_days", "no_date", "overdue"};
        for (ViewKind kind : new ViewKind[] {ViewKind.PROJECT, ViewKind.LABEL, ViewKind.FILTER}) {
            for (String value : all) {
                DisplayOptions options = DisplayParams.fromQuery(kind, due(value)).orElseThrow();
                assertThat(options.filterDue()).as(kind + " " + value)
                        .isEqualTo(DueFilter.valueOf(value.toUpperCase(Locale.ROOT)));
            }
        }
        for (String value : new String[] {"today", "this_week", "next_7_days", "this_month", "next_30_days"}) {
            assertThat(DisplayParams.fromQuery(ViewKind.TODAY, due(value))).as("TODAY " + value).isPresent();
            assertThat(DisplayParams.fromQuery(ViewKind.UPCOMING, due(value))).as("UPCOMING " + value).isPresent();
        }
    }

    @Test
    void filterPriority_mustBeOneToFour() {
        for (String ok : new String[] {"1", "2", "3", "4"}) {
            assertThat(DisplayParams.fromQuery(ViewKind.LABEL, new DisplayQuery(null, null, null, ok, null, null)))
                    .as(ok).isPresent();
        }
        for (String bad : new String[] {"0", "5", "-1", "1.5", "", " 2"}) {
            assertInvalid(ViewKind.LABEL, new DisplayQuery(null, null, null, bad, null, null));
        }
    }

    @Test
    void unknownOrNonLowercaseValues_areRejected() {
        assertInvalid(ViewKind.TODAY, query("Priority", null, null));
        assertInvalid(ViewKind.TODAY, query("PRIORITY", null, null));
        assertInvalid(ViewKind.TODAY, query("random", null, null));
        assertInvalid(ViewKind.TODAY, query(null, "up", null));
        assertInvalid(ViewKind.TODAY, query(null, "DESC", null));
        assertInvalid(ViewKind.TODAY, query("", null, null));
        assertInvalid(ViewKind.TODAY, new DisplayQuery(null, null, null, "5", null, null));
        assertInvalid(ViewKind.TODAY, new DisplayQuery(null, null, null, "x", null, null));
        assertInvalid(ViewKind.TODAY, new DisplayQuery(null, null, null, null, "not-a-uuid", null));
        assertInvalid(ViewKind.TODAY, due("soon"));
        assertInvalid(ViewKind.TODAY, due("THIS_WEEK"));
    }

    @Test
    void filterLabelId_mustBeACanonicalUuid() {
        UUID id = UUID.randomUUID();
        assertThat(DisplayParams.fromQuery(ViewKind.LABEL,
                new DisplayQuery(null, null, null, null, id.toString(), null)).orElseThrow().filterLabelId())
                .isEqualTo(id);
        assertInvalid(ViewKind.LABEL, new DisplayQuery(null, null, null, null, "1-1-1-1-1", null));
        assertInvalid(ViewKind.LABEL, new DisplayQuery(null, null, null, null, "", null));
        assertInvalid(ViewKind.LABEL, new DisplayQuery(null, null, null, null, id + "0", null));
    }

    @Test
    void validate_rejectsMissingPartsAndOutOfRangePriority() {
        DisplayRules.validate(ViewKind.TODAY, DisplayRules.defaults(ViewKind.TODAY));

        assertThatThrownBy(() -> DisplayRules.validate(ViewKind.TODAY,
                new DisplayOptions(null, SortDirection.ASC, GroupBy.NONE, null, null, null)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> DisplayRules.validate(ViewKind.TODAY,
                new DisplayOptions(SortBy.DATE, null, GroupBy.NONE, null, null, null)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> DisplayRules.validate(ViewKind.TODAY,
                new DisplayOptions(SortBy.DATE, SortDirection.ASC, null, null, null, null)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> DisplayRules.validate(ViewKind.TODAY,
                new DisplayOptions(SortBy.DATE, SortDirection.ASC, GroupBy.NONE, 5, null, null)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> DisplayRules.validate(ViewKind.TODAY,
                new DisplayOptions(SortBy.DATE, SortDirection.ASC, GroupBy.NONE, null, null, DueFilter.NO_DATE)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getBody().getDetail()).isEqualTo(DisplayTexts.DISPLAY_INVALID));
    }

    /**
     * Spec 5.1 / 12.7, quyết định #11 + review: cờ khối Quá hạn của Today đứng đầu cho MỌI chiều sắp (order=desc
     * chỉ đảo khoá ngày-giờ bên trong từng khối, không đưa khối Quá hạn xuống cuối). Task 18 dùng hàm / hằng này
     * để đặt chiều của cờ.
     */
    @Test
    void overdueBlockFlag_leadsOnTodayDateSortAndIgnoresDirection() {
        for (SortDirection direction : SortDirection.values()) {
            assertThat(DisplayRules.leadsWithOverdueBlock(ViewKind.TODAY,
                    new DisplayOptions(SortBy.DATE, direction, GroupBy.NONE, null, null, null))).isTrue();
        }
        assertThat(DisplayRules.OVERDUE_BLOCK_DESCENDING).isFalse();
    }

    @Test
    void overdueBlockFlag_doesNotApplyOutsideTodayDateSort() {
        for (SortBy sort : SortBy.values()) {
            if (sort != SortBy.DATE) {
                assertThat(DisplayRules.leadsWithOverdueBlock(ViewKind.TODAY,
                        new DisplayOptions(sort, SortDirection.ASC, GroupBy.NONE, null, null, null)))
                        .as("TODAY " + sort).isFalse();
            }
        }
        for (ViewKind kind : new ViewKind[] {ViewKind.UPCOMING, ViewKind.PROJECT, ViewKind.LABEL, ViewKind.FILTER}) {
            assertThat(DisplayRules.leadsWithOverdueBlock(kind, DisplayRules.defaults(kind))).as(kind.name())
                    .isFalse();
        }
    }

    @Test
    void displayQuery_isEmptyOnlyWhenNothingIsSent() {
        assertThat(new DisplayQuery(null, null, null, null, null, null).isEmpty()).isTrue();
        assertThat(new DisplayQuery(null, null, null, null, null, "today").isEmpty()).isFalse();
        assertThat(new DisplayQuery("", null, null, null, null, null).isEmpty()).isFalse();
    }
}
