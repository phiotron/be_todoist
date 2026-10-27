package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.common.error.ProblemTexts;
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

/** Display cho Today / Upcoming / dự án (spec 12.7). Giờ đóng băng 2026-11-04T03:00:00Z = 10:00 thứ Tư 04/11. */
class TaskDisplayApiTest extends M2ApiTestBase {

    private static final String TASKS = "/api/v1/tasks";

    private RegisteredUser user;
    private String token;
    private UUID inbox;

    @BeforeEach
    void setUp() throws Exception {
        clock.freezeAt(Instant.parse("2026-11-04T03:00:00Z"));
        user = registerUser(uniqueEmail());
        token = user.accessToken();
        inbox = data.inboxOf(user.id());
    }

    private static LocalDate d(int day) {
        return LocalDate.of(2026, 11, day);
    }

    private static String[] join(String[] first, String... more) {
        List<String> all = new ArrayList<>(List.of(first));
        all.addAll(List.of(more));
        return all.toArray(String[]::new);
    }

    private List<String> list(String[] selector, String... more) throws Exception {
        return contents(getOk(token, TASKS, join(selector, join(more, "limit", "200"))));
    }

    // ---------------------------------------------------------------- Today

    private static final String[] TODAY = {"view", "today"};

    /** od-day: 03/11 P3 · od-time: 04/11 08:00 P4 · today-date: 04/11 P1 · today-time: 04/11 18:00 P2 · tương lai / xong: loại. */
    private void todayData() {
        data.insert(TaskSpec.of(inbox, "od-day").dueDate(d(3)).priority(3));
        data.insert(TaskSpec.of(inbox, "od-time").dueDatetime(LocalDateTime.of(2026, 11, 4, 8, 0)).priority(4));
        data.insert(TaskSpec.of(inbox, "today-date").dueDate(d(4)).priority(1));
        data.insert(TaskSpec.of(inbox, "today-time").dueDatetime(LocalDateTime.of(2026, 11, 4, 18, 0)).priority(2));
        data.insert(TaskSpec.of(inbox, "future").dueDate(d(5)));
        data.insert(TaskSpec.of(inbox, "done").dueDate(d(4)).completedAt(Instant.parse("2026-11-01T00:00:00Z")));
    }

    @Test
    void today_sortDateEqualsTheM1DefaultOrder() throws Exception {
        todayData();

        // Khối Quá hạn = due_day < hôm nay (spec 5.1): chỉ od-day. od-time (08:00 < 10:00 hôm nay) thuộc khối Hôm nay.
        List<String> m1 = list(TODAY);
        assertThat(m1).containsExactly("od-day", "today-date", "od-time", "today-time");
        assertThat(list(TODAY, "sort", "date")).isEqualTo(m1);
        // Khối Quá hạn luôn đứng đầu Today (spec 5.1 / quy tắc chốt 3.1); order=desc chỉ đảo ngày-giờ trong từng khối.
        assertThat(list(TODAY, "sort", "date", "order", "desc"))
                .containsExactly("od-day", "today-time", "od-time", "today-date");
    }

    @Test
    void today_sortPriorityNameAndGroups() throws Exception {
        todayData();

        assertThat(list(TODAY, "sort", "priority")).containsExactly("today-date", "today-time", "od-day", "od-time");
        assertThat(list(TODAY, "sort", "priority", "order", "desc"))
                .containsExactly("od-time", "od-day", "today-time", "today-date");
        assertThat(list(TODAY, "sort", "name", "order", "desc"))
                .containsExactly("today-time", "today-date", "od-time", "od-day");
        assertThat(list(TODAY, "group", "priority")).containsExactly("today-date", "today-time", "od-day", "od-time");
    }

    @Test
    void today_groupByDeadlineThenDateSort() throws Exception {
        data.insert(TaskSpec.of(inbox, "od-day").dueDate(d(3)).deadline(d(8)));
        data.insert(TaskSpec.of(inbox, "od-time").dueDatetime(LocalDateTime.of(2026, 11, 4, 8, 0)));
        data.insert(TaskSpec.of(inbox, "today-date").dueDate(d(4)));
        data.insert(TaskSpec.of(inbox, "today-time").dueDatetime(LocalDateTime.of(2026, 11, 4, 18, 0)).deadline(d(10)));

        assertThat(list(TODAY, "group", "deadline")).containsExactly("od-day", "today-time", "today-date", "od-time");
    }

    @Test
    void today_displayFilters() throws Exception {
        todayData();

        assertThat(list(TODAY, "filterPriority", "1")).containsExactly("today-date");
        assertThat(list(TODAY, "filterDue", "overdue")).containsExactly("od-day", "od-time");
        assertThat(list(TODAY, "filterDue", "today")).containsExactly("today-date", "od-time", "today-time");
    }

    /** Spec 5.1, 18.1 C3: khối Quá hạn = due_day < hôm nay; filterDue=overdue = isOverdue (gồm task có giờ đã qua hôm nay). */
    @Test
    void today_overdueBlockUsesDueDay_whileFilterDueOverdueUsesIsOverdue() throws Exception {
        todayData();

        String json = getOk(token, TASKS, join(TODAY, "sort", "date", "limit", "200"));
        assertThat(contents(json)).containsExactly("od-day", "today-date", "od-time", "today-time");
        assertThat(com.jayway.jsonpath.JsonPath.<List<Boolean>>read(json, "$.items[*].isOverdue"))
                .containsExactly(true, false, true, false);   // od-time đỏ (isOverdue) nhưng KHÔNG thuộc khối Quá hạn
        assertThat(list(TODAY, "filterDue", "overdue")).containsExactly("od-day", "od-time");
    }

    /** Spec 12.7, 12.10, 18.1 m6. */
    @Test
    void today_filterDueNoDateIs400_upcomingNoDateAndOverdueAre400() throws Exception {
        apiGet(token, TASKS, join(TODAY, "filterDue", "no_date")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(ProblemTexts.DISPLAY_INVALID));
        apiGet(token, TASKS, "view", "upcoming", "filterDue", "no_date").andExpect(status().isBadRequest());
        apiGet(token, TASKS, "view", "upcoming", "filterDue", "overdue").andExpect(status().isBadRequest());
        apiGet(token, TASKS, join(TODAY, "filterDue", "overdue")).andExpect(status().isOk());
        apiGet(token, TASKS, "view", "upcoming", "filterDue", "next_7_days").andExpect(status().isOk());
    }

    @Test
    void today_notAllowedDisplayAndCombinationsAre400() throws Exception {
        todayData();

        for (String[] bad : new String[][] {{"sort", "manual"}, {"group", "date"}, {"group", "deadline", "sort", "x"}}) {
            apiGet(token, TASKS, join(TODAY, bad)).andExpect(status().isBadRequest());
        }
        apiGet(token, TASKS, "view", "today", "sort", "manual").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(ProblemTexts.DISPLAY_INVALID));
        apiGet(token, TASKS, "view", "overdue", "sort", "date").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(ProblemTexts.DISPLAY_INVALID));
        apiGet(token, TASKS, "view", "completed", "sort", "date").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(ProblemTexts.DISPLAY_INVALID));   // màn Completed toàn cục là của M1
        apiGet(token, TASKS, "view", "today", "since", "2026-11-01", "sort", "date").andExpect(status().isBadRequest());
        apiGet(token, TASKS, "view", "today", "to", "2026-11-09", "sort", "date").andExpect(status().isBadRequest());
        apiGet(token, TASKS, "view", "today", "projectId", inbox.toString(), "sort", "date")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(ProblemTexts.TASK_LIST_SELECTOR));
        apiGet(token, TASKS, "view", "today", "from", "2026-11-05", "sort", "date").andExpect(status().isBadRequest());
        apiGet(token, TASKS, "view", "today", "completed", "true", "sort", "date").andExpect(status().isBadRequest());
        apiGet(token, TASKS, "projectId", inbox.toString(), "completed", "true", "sort", "name")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(ProblemTexts.DISPLAY_INVALID));
    }

    @Test
    void today_pagingWithDisplayMatchesOnePage() throws Exception {
        for (int i = 1; i <= 7; i++) {
            data.insert(TaskSpec.of(inbox, "t" + i).dueDate(d(1 + i % 3)).priority(1 + i % 4));
        }
        List<String> onePage = list(TODAY, "sort", "name", "order", "desc");
        assertThat(onePage).hasSize(7);

        for (int limit = 1; limit <= 3; limit++) {
            assertThat(collectAllContents(token, TASKS, limit, "view", "today", "sort", "name", "order", "desc"))
                    .isEqualTo(onePage);
        }
    }

    // ------------------------------------------------------------- Upcoming

    private static final String[] UPCOMING = {"view", "upcoming"};

    /** u1: 04/11 P3 · u2: 05/11 P1 · u3: 05/11 P2 · u4: 06/11 P4 · quá khứ / không due: loại. */
    private void upcomingData() {
        data.insert(TaskSpec.of(inbox, "u1").dueDate(d(4)).priority(3));
        data.insert(TaskSpec.of(inbox, "u2").dueDate(d(5)).priority(1));
        data.insert(TaskSpec.of(inbox, "u3").dueDate(d(5)).priority(2));
        data.insert(TaskSpec.of(inbox, "u4").dueDate(d(6)).priority(4));
        data.insert(TaskSpec.of(inbox, "past").dueDate(d(3)));
        data.insert(TaskSpec.of(inbox, "nodate"));
    }

    @Test
    void upcoming_sortDateEqualsTheM1DefaultOrder_andDaysAreAlwaysGroupedFirst() throws Exception {
        upcomingData();

        List<String> m1 = list(UPCOMING);
        assertThat(m1).containsExactly("u1", "u2", "u3", "u4");
        assertThat(list(UPCOMING, "sort", "date")).isEqualTo(m1);
        assertThat(list(UPCOMING, "sort", "priority")).containsExactly("u1", "u2", "u3", "u4");
        assertThat(list(UPCOMING, "sort", "name", "order", "desc")).containsExactly("u1", "u3", "u2", "u4");
        assertThat(list(UPCOMING, "sort", "priority", "order", "desc")).containsExactly("u1", "u3", "u2", "u4");
    }

    @Test
    void upcoming_fromAndDisplayFilters_andGroupOtherThanNoneIs400() throws Exception {
        upcomingData();

        assertThat(list(UPCOMING, "from", "2026-11-06", "sort", "date")).containsExactly("u4");
        assertThat(list(UPCOMING, "from", "2026-11-01", "sort", "date")).containsExactly("u1", "u2", "u3", "u4");   // phần đầu kẹp ở hôm nay
        assertThat(list(UPCOMING, "filterPriority", "2")).containsExactly("u3");
        assertThat(list(UPCOMING, "filterDue", "today")).containsExactly("u1");
        apiGet(token, TASKS, join(UPCOMING, "group", "date")).andExpect(status().isBadRequest());
        apiGet(token, TASKS, join(UPCOMING, "group", "none")).andExpect(status().isOk());
    }

    /** Cửa sổ ngày của Upcoming (spec 12.3, 18.1 I9): to mặc định from + 13, cửa sổ ≤ 31 ngày, tầm xem ≤ hôm nay + 2 năm. */
    @Test
    void upcoming_followsTheM1DateWindow() throws Exception {
        data.insert(TaskSpec.of(inbox, "d13").dueDate(d(4).plusDays(13)));   // 17/11 = hôm nay + 13 → trong cửa sổ mặc định
        data.insert(TaskSpec.of(inbox, "d14").dueDate(d(4).plusDays(14)));   // 18/11 → ngoài cửa sổ mặc định
        data.insert(TaskSpec.of(inbox, "d30").dueDate(d(4).plusDays(30)));   // 04/12

        assertThat(list(UPCOMING, "sort", "date")).containsExactly("d13");
        assertThat(list(UPCOMING, "sort", "date", "to", "2026-11-18")).containsExactly("d13", "d14");
        assertThat(list(UPCOMING, "from", "2026-11-18", "to", "2026-11-18", "sort", "date")).containsExactly("d14");
        assertThat(list(UPCOMING, "from", "2026-11-04", "to", "2026-12-04", "sort", "date")).hasSize(3);   // 31 ngày: vừa đủ
        apiGet(token, TASKS, join(UPCOMING, "from", "2026-11-04", "to", "2026-12-05", "sort", "date"))   // 32 ngày
                .andExpect(status().isBadRequest());
        apiGet(token, TASKS, join(UPCOMING, "from", "2026-11-10", "to", "2026-11-09", "sort", "date"))
                .andExpect(status().isBadRequest());
        apiGet(token, TASKS, join(UPCOMING, "from", "2028-11-05", "sort", "date")).andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------- Dự án

    private UUID work;

    /**
     * Dự án Work: không section: r0 (P3, d09, deadline 20/11, sort 1000) ·
     * S1: a (P2, d08, sort 1000; con a1 → cháu a11, con a2) · b (P1, d07, sort 2000) ·
     * S2: c (P4, không due, deadline 15/11, sort 1000) · d đã xong (loại).
     */
    private void projectData() {
        work = data.project(user.id(), "Work");
        UUID s1 = data.section(work, "S1", 1_000);
        UUID s2 = data.section(work, "S2", 2_000);
        data.insert(TaskSpec.of(work, "r0").priority(3).dueDate(d(9)).deadline(LocalDate.of(2026, 11, 20)).sortOrder(1_000));
        UUID a = data.insert(TaskSpec.of(work, "a").section(s1).priority(2).dueDate(d(8)).sortOrder(1_000));
        UUID a1 = data.insert(TaskSpec.of(work, "a1").section(s1).parent(a).sortOrder(1_000));
        data.insert(TaskSpec.of(work, "a11").section(s1).parent(a1).sortOrder(1_000));
        data.insert(TaskSpec.of(work, "a2").section(s1).parent(a).sortOrder(2_000).completedAt(Instant.parse("2026-11-02T00:00:00Z")));
        data.insert(TaskSpec.of(work, "b").section(s1).priority(1).dueDate(d(7)).sortOrder(2_000));
        data.insert(TaskSpec.of(work, "c").section(s2).priority(4).deadline(LocalDate.of(2026, 11, 15)).sortOrder(1_000));
        data.insert(TaskSpec.of(work, "d").section(s2).completedAt(Instant.parse("2026-11-02T00:00:00Z")));
    }

    private String[] project() {
        return new String[] {"projectId", work.toString()};
    }

    @Test
    void project_manualSortEqualsTheM1Order_subtasksFollowTheirParent_includingCompletedChildren() throws Exception {
        projectData();

        List<String> m1 = list(project());
        assertThat(m1).containsExactly("r0", "a", "a1", "a11", "a2", "b", "c");
        assertThat(list(project(), "sort", "manual")).isEqualTo(m1);
        apiGet(token, TASKS, join(project(), "sort", "manual"))
                .andExpect(jsonPath("$.items[2].parentContent").value("a"));
    }

    @Test
    void project_sortWithinSection_andGroupAcrossSections() throws Exception {
        projectData();

        assertThat(list(project(), "sort", "priority")).containsExactly("r0", "b", "a", "a1", "a11", "a2", "c");
        assertThat(list(project(), "sort", "date", "order", "desc")).containsExactly("r0", "a", "a1", "a11", "a2", "b", "c");
        assertThat(list(project(), "group", "priority", "sort", "name")).containsExactly("b", "a", "a1", "a11", "a2", "r0", "c");
        assertThat(list(project(), "group", "date")).containsExactly("b", "a", "a1", "a11", "a2", "r0", "c");
        assertThat(list(project(), "group", "deadline")).containsExactly("c", "r0", "a", "a1", "a11", "a2", "b");
    }

    @Test
    void project_displayFiltersApplyToRootsOnly() throws Exception {
        projectData();

        assertThat(list(project(), "filterPriority", "1")).containsExactly("b");
        assertThat(list(project(), "filterPriority", "2")).containsExactly("a", "a1", "a11", "a2");
        assertThat(list(project(), "filterDue", "next_7_days")).containsExactly("r0", "a", "a1", "a11", "a2", "b");
        assertThat(list(project(), "filterDue", "this_week")).containsExactly("a", "a1", "a11", "a2", "b");
        assertThat(list(project(), "filterDue", "no_date")).containsExactly("c");
    }

    @Test
    void project_groupProjectIsNotAllowed_foreignProjectIs404_archivedProjectIsReadable() throws Exception {
        projectData();
        UUID archived = data.project(user.id(), "Cũ");
        data.insert(TaskSpec.of(archived, "old-open"));
        data.archiveProject(archived);
        UUID foreign = data.project(registerUser(uniqueEmail()).id(), "Của người khác");

        apiGet(token, TASKS, join(project(), "group", "project")).andExpect(status().isBadRequest());
        apiGet(token, TASKS, "projectId", foreign.toString(), "sort", "name").andExpect(status().isNotFound());
        apiGet(token, TASKS, "projectId", UUID.randomUUID().toString(), "sort", "name").andExpect(status().isNotFound());
        assertThat(list(new String[] {"projectId", archived.toString()}, "sort", "name")).containsExactly("old-open");
    }

    @Test
    void project_inboxWorksToo_andPagingKeepsRootsWithTheirSubtrees() throws Exception {
        projectData();
        data.insert(TaskSpec.of(inbox, "inbox-task"));
        assertThat(list(new String[] {"projectId", inbox.toString()}, "sort", "name")).containsExactly("inbox-task");

        String first = getOk(token, TASKS, join(project(), "sort", "manual", "limit", "2"));
        assertThat(contents(first)).containsExactly("r0", "a", "a1", "a11", "a2");   // 2 gốc + cây con của chúng
        String second = getOk(token, TASKS, join(project(), "sort", "manual", "limit", "2", "cursor", nextCursor(first)));
        assertThat(contents(second)).containsExactly("b", "c");
        apiGet(token, TASKS, join(project(), "sort", "manual", "limit", "2")).andExpect(jsonPath("$.nextCursor").isNotEmpty());

        // limit = số task GỐC mỗi trang; mỗi trang kèm đủ cây con nên số phần tử có thể vượt limit
        List<String> all = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 10; page++) {
            List<String> params = new ArrayList<>(List.of("projectId", work.toString(), "sort", "manual", "limit", "1"));
            if (cursor != null) {
                params.addAll(List.of("cursor", cursor));
            }
            String json = getOk(token, TASKS, params.toArray(String[]::new));
            all.addAll(contents(json));
            cursor = nextCursor(json);
            if (cursor == null) {
                break;
            }
        }
        assertThat(all).containsExactly("r0", "a", "a1", "a11", "a2", "b", "c");
    }

    @Test
    void project_savedDisplayIsUsedByTheAppNotByTheServer() throws Exception {
        projectData();
        apiPut(token, "/api/v1/view-options/project:" + work,
                "{\"sortBy\": \"NAME\", \"sortDirection\": \"DESC\", \"groupBy\": \"NONE\"}").andExpect(status().isOk());

        assertThat(list(project())).containsExactly("r0", "a", "a1", "a11", "a2", "b", "c");   // server không tự áp
        apiGet(token, TASKS, join(project(), "sort", "name", "order", "desc"))
                .andExpect(jsonPath("$.items[*].content", contains("r0", "b", "a", "a1", "a11", "a2", "c")));
    }
}
