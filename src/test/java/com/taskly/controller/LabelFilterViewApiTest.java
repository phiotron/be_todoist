package com.taskly.controller;

import com.taskly.common.error.ErrorMessage;
import com.taskly.support.Texts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


import com.taskly.support.M2ApiTestBase;
import com.taskly.support.M2TestData.TaskSpec;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * GET /api/v1/tasks?labelId= và ?filter= cùng Display (spec 12.7). Giờ đóng băng 2026-11-04T03:00:00Z =
 * 10:00 thứ Tư 04/11 ở Asia/Ho_Chi_Minh.
 */
class LabelFilterViewApiTest extends M2ApiTestBase {

    private static final String TASKS = "/api/v1/tasks";

    private RegisteredUser user;
    private String token;
    private UUID inbox;
    private UUID home;
    private UUID urgent;

    @BeforeEach
    void setUp() throws Exception {
        clock.freezeAt(Instant.parse("2026-11-04T03:00:00Z"));
        user = registerUser(uniqueEmail());
        token = user.accessToken();
        inbox = data.inboxOf(user.id());
        urgent = data.label(user.id(), "gap", 1_000);
        home = data.label(user.id(), "nha", 2_000);
    }

    private static LocalDate d(int day) {
        return LocalDate.of(2026, 11, day);
    }

    private TaskSpec spec(String content) {
        return TaskSpec.of(inbox, content);
    }

    private UUID tagged(TaskSpec spec, UUID... labels) {
        UUID id = data.insert(spec);
        data.tag(id, labels);
        return id;
    }

    private static String[] params(String first, String second, String... more) {
        List<String> all = new ArrayList<>(List.of(first, second));
        all.addAll(List.of(more));
        return all.toArray(String[]::new);
    }

    private List<String> byLabel(String... more) throws Exception {
        return contents(getOk(token, TASKS, params("labelId", home.toString(), more)));
    }

    private List<String> byFilter(String query, String... more) throws Exception {
        return contents(getOk(token, TASKS, params("filter", query, more)));
    }

    @Test
    void label_listsOpenTasksIncludingSubtasks_excludingCompletedAndArchivedProjects() throws Exception {
        UUID c = data.insert(spec("C"));
        tagged(spec("A").dueDate(d(5)), home);
        tagged(spec("B").completedAt(Instant.parse("2026-11-01T00:00:00Z")), home);
        tagged(spec("E").parent(c).dueDate(d(6)), home);
        UUID archived = data.project(user.id(), "Cũ");
        tagged(TaskSpec.of(archived, "F"), home);
        data.archiveProject(archived);

        apiGet(token, TASKS, "labelId", home.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].content", contains("A", "E")))
                .andExpect(jsonPath("$.items[0].projectName").value("Inbox"))
                .andExpect(jsonPath("$.items[1].parentContent").value("C"))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void label_ofAnotherUserOrMissing_is404_andForeignTasksAreInvisible() throws Exception {
        var other = registerUser(uniqueEmail());
        UUID foreignLabel = data.label(other.id(), "nha");
        tagged(TaskSpec.of(data.inboxOf(other.id()), "Của người khác"), foreignLabel);

        apiGet(token, TASKS, "labelId", foreignLabel.toString()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(Texts.vi(ErrorMessage.LABEL_NOT_FOUND)));
        apiGet(token, TASKS, "labelId", UUID.randomUUID().toString()).andExpect(status().isNotFound());
        assertThat(byLabel()).isEmpty();
    }

    /** alpha: không due, P1; Zeta: 07/11, P2; Mike: 05/11, P3; Beta: 06/11, P4. */
    private void fourTasks() {
        tagged(spec("alpha").priority(1).createdAt(Instant.parse("2026-10-01T00:00:00Z"))
                .sortOrder(10), home);
        tagged(spec("Zeta").priority(2).dueDate(d(7)).deadline(d(12)).createdAt(Instant.parse("2026-10-04T00:00:00Z"))
                .sortOrder(20), home);
        tagged(spec("Mike").priority(3).dueDate(d(5)).deadline(d(9)).createdAt(Instant.parse("2026-10-02T00:00:00Z"))
                .sortOrder(30), home);
        tagged(spec("Beta").priority(4).dueDate(d(6)).deadline(d(8)).createdAt(Instant.parse("2026-10-03T00:00:00Z"))
                .sortOrder(40), home);
    }

    @Test
    void sort_dateIsDefault_noDateAlwaysLast_inBothDirections() throws Exception {
        fourTasks();

        assertThat(byLabel()).containsExactly("Mike", "Beta", "Zeta", "alpha");
        assertThat(byLabel("sort", "date", "order", "asc")).containsExactly("Mike", "Beta", "Zeta", "alpha");
        assertThat(byLabel("sort", "date", "order", "desc")).containsExactly("Zeta", "Beta", "Mike", "alpha");
    }

    @Test
    void sort_deadlinePriorityNameAdded() throws Exception {
        fourTasks();

        assertThat(byLabel("sort", "deadline")).containsExactly("Beta", "Mike", "Zeta", "alpha");
        assertThat(byLabel("sort", "deadline", "order", "desc")).containsExactly("Zeta", "Mike", "Beta", "alpha");
        assertThat(byLabel("sort", "priority")).containsExactly("alpha", "Zeta", "Mike", "Beta");
        assertThat(byLabel("sort", "priority", "order", "desc")).containsExactly("Beta", "Mike", "Zeta", "alpha");
        assertThat(byLabel("sort", "name")).containsExactly("alpha", "Beta", "Mike", "Zeta");
        assertThat(byLabel("sort", "name", "order", "desc")).containsExactly("Zeta", "Mike", "Beta", "alpha");
        assertThat(byLabel("sort", "added")).containsExactly("alpha", "Mike", "Beta", "Zeta");
        assertThat(byLabel("sort", "added", "order", "desc")).containsExactly("Zeta", "Beta", "Mike", "alpha");
    }

    @Test
    void group_priorityDateAndDeadline_orderGroupsFirstThenSort() throws Exception {
        tagged(spec("a1").priority(1).dueDate(d(6)), home);
        tagged(spec("b1").priority(1).dueDate(d(6)), home);
        tagged(spec("c2").priority(2).dueDate(d(5)), home);
        tagged(spec("d2").priority(2), home);

        assertThat(byLabel("group", "priority", "sort", "name", "order", "desc"))
                .containsExactly("b1", "a1", "d2", "c2");
        assertThat(byLabel("group", "date", "sort", "name")).containsExactly("c2", "a1", "b1", "d2");
        assertThat(byLabel("group", "deadline", "sort", "name")).containsExactly("a1", "b1", "c2", "d2");
    }

    @Test
    void group_projectFollowsProjectTree_parentBeforeChildBeforeNextSibling() throws Exception {
        UUID first = data.project(user.id(), "P1");
        UUID child = data.project(user.id(), first, "P1-con");
        UUID second = data.project(user.id(), "P2");
        tagged(TaskSpec.of(second, "p2a"), home);
        tagged(TaskSpec.of(child, "c1"), home);
        tagged(TaskSpec.of(first, "p1b"), home);
        tagged(TaskSpec.of(first, "p1a"), home);

        assertThat(byLabel("group", "project", "sort", "name")).containsExactly("p1a", "p1b", "c1", "p2a");
    }

    @Test
    void group_labelUsesTheFirstLabelByLabelOrder_untaggedLast() throws Exception {
        tagged(spec("t-both"), home, urgent);   // label đầu theo sort_order: gap (1000)
        tagged(spec("t-gap"), urgent);
        tagged(spec("t-nha"), home);
        data.insert(spec("t-none"));

        assertThat(byFilter("no date", "group", "label", "sort", "name"))
                .containsExactly("t-both", "t-gap", "t-nha", "t-none");
    }

    @Test
    void displayFilters_priorityLabelAndDue() throws Exception {
        tagged(spec("yday").dueDate(d(3)), home);
        tagged(spec("d0").dueDate(d(4)).priority(1), home);
        tagged(spec("d4").dueDate(d(8)), home, urgent);
        tagged(spec("d5").dueDate(d(9)), home);
        tagged(spec("d6").dueDate(d(10)), home);
        tagged(spec("d7").dueDate(d(11)), home);
        tagged(spec("m30").dueDate(d(30)), home);
        tagged(spec("n1").dueDate(LocalDate.of(2026, 12, 1)), home);
        tagged(spec("n29").dueDate(LocalDate.of(2026, 12, 3)), home);
        tagged(spec("n30").dueDate(LocalDate.of(2026, 12, 4)), home);
        tagged(spec("nodate"), home);

        assertThat(byLabel("filterDue", "today")).containsExactly("d0");
        assertThat(byLabel("filterDue", "this_week")).containsExactly("d0", "d4");
        assertThat(byLabel("filterDue", "next_7_days")).containsExactly("d0", "d4", "d5", "d6");
        assertThat(byLabel("filterDue", "this_month")).containsExactly("d0", "d4", "d5", "d6", "d7", "m30");
        assertThat(byLabel("filterDue", "next_30_days"))
                .containsExactly("d0", "d4", "d5", "d6", "d7", "m30", "n1", "n29");
        assertThat(byLabel("filterDue", "no_date")).containsExactly("nodate");
        assertThat(byLabel("filterDue", "overdue")).containsExactly("yday");
        assertThat(byLabel("filterPriority", "1")).containsExactly("d0");
        assertThat(byLabel("filterPriority", "1", "filterDue", "this_week")).containsExactly("d0");
        assertThat(byLabel("filterLabelId", urgent.toString())).containsExactly("d4");
    }

    /** Spec 12.7, 18.1 C3: filterDue=overdue theo isOverdue (giờ đã qua) — gồm cả task có giờ đã qua TRONG hôm nay. */
    @Test
    void displayFilterOverdue_usesIsOverdue_includingTimedTasksEarlierToday() throws Exception {
        tagged(spec("yday").dueDate(d(3)), home);                                                // due_day < hôm nay
        tagged(spec("timed-past").dueDatetime(LocalDateTime.of(2026, 11, 4, 8, 0)), home);        // 08:00 < 10:00 hôm nay
        tagged(spec("timed-future").dueDatetime(LocalDateTime.of(2026, 11, 4, 18, 0)), home);     // chưa tới giờ
        tagged(spec("today-date").dueDate(d(4)), home);                                          // cả ngày hôm nay: chưa quá hạn

        assertThat(byLabel("filterDue", "overdue")).containsExactly("yday", "timed-past");
        assertThat(byLabel("filterDue", "today")).containsExactly("today-date", "timed-past", "timed-future");
    }

    @Test
    void invalidSelectorsAndDisplayAreRejected() throws Exception {
        String label = home.toString();
        UUID foreign = data.label(registerUser(uniqueEmail()).id(), "x");

        apiGet(token, TASKS, "labelId", label, "filter", "p1").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(Texts.vi(ErrorMessage.TASK_LIST_SELECTOR)));
        apiGet(token, TASKS, "labelId", label, "view", "today").andExpect(status().isBadRequest());
        apiGet(token, TASKS, "labelId", label, "projectId", inbox.toString()).andExpect(status().isBadRequest());
        apiGet(token, TASKS, "labelId", label, "completed", "true").andExpect(status().isBadRequest());
        apiGet(token, TASKS, "labelId", label, "from", "2026-11-05").andExpect(status().isBadRequest());
        apiGet(token, TASKS, "labelId", label, "to", "2026-11-09").andExpect(status().isBadRequest());
        apiGet(token, TASKS, "labelId", label, "since", "2026-11-01").andExpect(status().isBadRequest());
        apiGet(token, TASKS, "labelId", "not-a-uuid").andExpect(status().isBadRequest());

        for (String[] bad : new String[][] {{"sort", "manual"}, {"sort", "DATE"}, {"sort", "random"},
                {"order", "up"}, {"group", "section"}, {"filterPriority", "5"}, {"filterDue", "soon"},
                {"filterLabelId", "xyz"}}) {
            apiGet(token, TASKS, "labelId", label, bad[0], bad[1]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(Texts.vi(ErrorMessage.DISPLAY_INVALID)));
        }
        apiGet(token, TASKS, "labelId", label, "filterLabelId", foreign.toString()).andExpect(status().isNotFound());
        apiGet(token, TASKS, "labelId", label, "limit", "0").andExpect(status().isBadRequest());
    }

    /** Khoá ngày-giờ = due_sort: task chỉ ngày (00:00) đứng trước task có giờ cùng ngày; không due luôn cuối. */
    @Test
    void sort_dateOrdersByDueSort_dateOnlyBeforeTimedOfTheSameDay() throws Exception {
        tagged(spec("timed-20h").dueDatetime(LocalDateTime.of(2026, 11, 5, 20, 0)), home);
        tagged(spec("timed-09h").dueDatetime(LocalDateTime.of(2026, 11, 5, 9, 0)), home);
        tagged(spec("date-only").dueDate(d(5)), home);
        tagged(spec("later").dueDate(d(6)), home);
        tagged(spec("undated"), home);

        assertThat(byLabel()).containsExactly("date-only", "timed-09h", "timed-20h", "later", "undated");
        assertThat(byLabel("order", "desc")).containsExactly("later", "timed-20h", "timed-09h", "date-only", "undated");
    }

    /** Khoá null (không due / deadline / label) đi qua con trỏ ở cả hai chiều: đi từng trang = một trang. */
    @Test
    void paging_withNullKeys_matchesOnePageForEveryGroupAndDirection() throws Exception {
        tagged(spec("a").dueDate(d(5)).deadline(d(9)), home, urgent);
        tagged(spec("b"), home);
        tagged(spec("c").dueDate(d(5)), home, urgent);
        tagged(spec("d").deadline(d(8)), home);
        tagged(spec("e").dueDatetime(LocalDateTime.of(2026, 11, 5, 9, 30)), home, urgent);
        tagged(spec("f"), home);

        for (String group : new String[] {"none", "date", "deadline", "label", "project", "priority"}) {
            for (String sort : new String[] {"date", "deadline", "name"}) {
                for (String order : new String[] {"asc", "desc"}) {
                    List<String> onePage = byLabel("group", group, "sort", sort, "order", order, "limit", "50");
                    assertThat(onePage).as("%s/%s/%s", group, sort, order).hasSize(6);
                    for (int limit = 1; limit <= 2; limit++) {
                        assertThat(collectAllContents(token, TASKS, limit, "labelId", home.toString(), "group", group,
                                "sort", sort, "order", order)).as("%s/%s/%s limit %d", group, sort, order, limit)
                                .isEqualTo(onePage);
                    }
                }
            }
        }
    }

    @Test
    void paging_withDisplay_sameOrderAsOnePage_andCursorOfOtherDisplayIsRejected() throws Exception {
        for (int i = 1; i <= 7; i++) {
            tagged(spec("t" + i).priority(1 + i % 3).dueDate(d(i)), home);
        }
        List<String> onePage = byLabel("group", "priority", "sort", "name", "order", "desc", "limit", "50");
        assertThat(onePage).hasSize(7);

        for (int limit = 1; limit <= 3; limit++) {
            assertThat(collectAllContents(token, TASKS, limit, "labelId", home.toString(), "group", "priority",
                    "sort", "name", "order", "desc")).isEqualTo(onePage);
        }
        String cursor = nextCursor(getOk(token, TASKS, "labelId", home.toString(), "group", "priority", "sort", "name",
                "limit", "2"));
        apiGet(token, TASKS, "labelId", home.toString(), "group", "priority", "sort", "date", "cursor", cursor)
                .andExpect(status().isBadRequest());
        apiGet(token, TASKS, "labelId", home.toString(), "group", "priority", "sort", "name", "order", "desc",
                "cursor", cursor).andExpect(status().isBadRequest());
    }

    @Test
    void filter_withDisplay_groupsAndSortsMatchedTasks() throws Exception {
        UUID work = data.project(user.id(), "Work");
        data.insert(TaskSpec.of(work, "w2"));
        data.insert(TaskSpec.of(work, "w1"));
        data.insert(spec("i1"));
        data.insert(spec("dated").dueDate(d(9)));

        assertThat(byFilter("no date", "group", "project", "sort", "name")).containsExactly("i1", "w1", "w2");
        assertThat(byFilter("no date", "sort", "name", "order", "desc")).containsExactly("w2", "w1", "i1");
    }

    @Test
    void filter_invalidOrCommaQuery_is400FilterSyntax() throws Exception {
        apiGet(token, TASKS, "filter", "today, p1").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("filter-syntax"))
                .andExpect(jsonPath("$.position").value(5))
                .andExpect(jsonPath("$.reason").value("comma"));
        apiGet(token, TASKS, "filter", "p1 |").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("filter-syntax"))
                .andExpect(jsonPath("$.position").value(4));
        apiGet(token, TASKS, "filter", "").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("filter-syntax"));
        apiGet(token, TASKS, "filter", "p1", "view", "today").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(Texts.vi(ErrorMessage.TASK_LIST_SELECTOR)));
    }
}
