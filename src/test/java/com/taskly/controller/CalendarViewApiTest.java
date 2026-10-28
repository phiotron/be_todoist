package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.common.error.ErrorMessage;
import com.taskly.support.M2ApiTestBase;
import com.taskly.support.M2TestData.TaskSpec;
import com.taskly.support.Texts;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * M7: GET tasks?view=calendar&from=&to= (spec 12.9). Giờ đóng băng 2026-11-04T03:00:00Z = 10:00 thứ Tư 04/11 (VN).
 * Calendar nhận cả ngày đã qua (lưới tháng), mọi cấp task, và tối đa một phạm vi projectId / labelId / filter.
 */
class CalendarViewApiTest extends M2ApiTestBase {

    private static final String TASKS = "/api/v1/tasks";
    private static final String[] NOVEMBER = {"view", "calendar", "from", "2026-11-01", "to", "2026-11-30"};

    private RegisteredUser user;
    private String token;
    private UUID inbox;
    private UUID work;

    @BeforeEach
    void setUp() throws Exception {
        clock.freezeAt(Instant.parse("2026-11-04T03:00:00Z"));
        user = registerUser(uniqueEmail());
        token = user.accessToken();
        inbox = data.inboxOf(user.id());
        work = data.project(user.id(), "Work");
    }

    private static LocalDate d(int day) {
        return LocalDate.of(2026, 11, day);
    }

    private static String[] join(String[] first, String... more) {
        List<String> all = new ArrayList<>(List.of(first));
        all.addAll(List.of(more));
        return all.toArray(String[]::new);
    }

    private List<String> list(String... params) throws Exception {
        return contents(getOk(token, TASKS, join(params, "limit", "200")));
    }

    /** past 01/11 · timed 05/11 09:00 · day 05/11 P1 · sub 06/11 (con của past) · work 07/11 · ngoài khoảng / không due / xong / dự án lưu trữ: loại. */
    private void sample() {
        UUID past = data.insert(TaskSpec.of(inbox, "past").dueDate(d(1)));
        data.insert(TaskSpec.of(inbox, "timed").dueDatetime(LocalDateTime.of(2026, 11, 5, 9, 0)).priority(4));
        data.insert(TaskSpec.of(inbox, "day").dueDate(d(5)).priority(1));
        data.insert(TaskSpec.of(inbox, "sub").parent(past).dueDate(d(6)));
        data.insert(TaskSpec.of(work, "work").dueDate(d(7)));
        data.insert(TaskSpec.of(inbox, "december").dueDate(LocalDate.of(2026, 12, 1)));
        data.insert(TaskSpec.of(inbox, "no-date"));
        data.insert(TaskSpec.of(inbox, "done").dueDate(d(5)).completedAt(Instant.parse("2026-11-04T01:00:00Z")));
        UUID archived = data.project(user.id(), "Cũ");
        data.insert(TaskSpec.of(archived, "archived").dueDate(d(5)));
        data.archiveProject(archived);
    }

    @Test
    void calendar_listsOpenDatedTasksOfEveryLevelInTheWindow_byDueThenPriority() throws Exception {
        sample();

        // 05/11: "day" (cả ngày = 00:00) trước "timed" 09:00 — cùng khoá due_sort như Upcoming.
        assertThat(list(NOVEMBER)).containsExactly("past", "day", "timed", "sub", "work");
        apiGet(token, TASKS, NOVEMBER).andExpect(jsonPath("$.items[3].parentId").isNotEmpty());
    }

    @Test
    void calendar_scopeByProjectLabelOrFilter() throws Exception {
        sample();
        UUID label = data.label(user.id(), "nha");
        UUID tagged = data.insert(TaskSpec.of(work, "tagged").dueDate(d(8)));
        data.tag(tagged, label);

        assertThat(list(join(NOVEMBER, "projectId", work.toString()))).containsExactly("work", "tagged");
        assertThat(list(join(NOVEMBER, "projectId", inbox.toString()))).containsExactly("past", "day", "timed", "sub");
        assertThat(list(join(NOVEMBER, "labelId", label.toString()))).containsExactly("tagged");
        assertThat(list(join(NOVEMBER, "filter", "#Work"))).containsExactly("work", "tagged");
        assertThat(list(join(NOVEMBER, "filter", "p1"))).containsExactly("day");
    }

    @Test
    void calendar_archivedProjectIsReadable_whenItIsTheScope() throws Exception {
        UUID archived = data.project(user.id(), "Cũ");
        data.insert(TaskSpec.of(archived, "old").dueDate(d(9)));
        data.archiveProject(archived);

        assertThat(list(NOVEMBER)).isEmpty();
        assertThat(list(join(NOVEMBER, "projectId", archived.toString()))).containsExactly("old");
    }

    @Test
    void calendar_appliesDisplayFilters() throws Exception {
        sample();
        UUID label = data.label(user.id(), "nha");
        UUID tagged = data.insert(TaskSpec.of(inbox, "tagged").dueDate(d(8)));
        data.tag(tagged, label);

        assertThat(list(join(NOVEMBER, "filterPriority", "1"))).containsExactly("day");
        assertThat(list(join(NOVEMBER, "filterLabelId", label.toString()))).containsExactly("tagged");
        assertThat(list(join(NOVEMBER, "filterDue", "today"))).isEmpty();
        assertThat(list(join(NOVEMBER, "filterDue", "overdue"))).containsExactly("past");
    }

    @Test
    void calendar_pagesWithoutDuplicates() throws Exception {
        sample();
        assertThat(collectAllContents(token, TASKS, 2, NOVEMBER)).containsExactly("past", "day", "timed", "sub",
                "work");
    }

    @Test
    void calendar_windowRules() throws Exception {
        String invalid = Texts.vi(ErrorMessage.CALENDAR_WINDOW_INVALID);
        apiGet(token, TASKS, "view", "calendar").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(invalid));
        apiGet(token, TASKS, "view", "calendar", "from", "2026-11-01").andExpect(status().isBadRequest());
        apiGet(token, TASKS, "view", "calendar", "to", "2026-11-01").andExpect(status().isBadRequest());
        apiGet(token, TASKS, "view", "calendar", "from", "2026-11-10", "to", "2026-11-09")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(invalid));
        // 42 ngày (lưới tháng 6 tuần) được, 43 ngày → 400.
        apiGet(token, TASKS, "view", "calendar", "from", "2026-10-26", "to", "2026-12-06").andExpect(status().isOk());
        apiGet(token, TASKS, "view", "calendar", "from", "2026-10-26", "to", "2026-12-07")
                .andExpect(status().isBadRequest());
        // Một ngày được; tầm xem ± 2 năm.
        apiGet(token, TASKS, "view", "calendar", "from", "2026-11-04", "to", "2026-11-04").andExpect(status().isOk());
        apiGet(token, TASKS, "view", "calendar", "from", "2024-11-04", "to", "2024-11-10").andExpect(status().isOk());
        apiGet(token, TASKS, "view", "calendar", "from", "2024-11-03", "to", "2024-11-10")
                .andExpect(status().isBadRequest());
        apiGet(token, TASKS, "view", "calendar", "from", "2028-11-01", "to", "2028-11-05")
                .andExpect(status().isBadRequest());
    }

    @Test
    void calendar_rejectsSortGroupNoDateAndSecondScope() throws Exception {
        UUID label = data.label(user.id(), "nha");
        apiGet(token, TASKS, join(NOVEMBER, "sort", "name")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(Texts.vi(ErrorMessage.DISPLAY_INVALID)));
        apiGet(token, TASKS, join(NOVEMBER, "order", "desc")).andExpect(status().isBadRequest());
        apiGet(token, TASKS, join(NOVEMBER, "group", "priority")).andExpect(status().isBadRequest());
        apiGet(token, TASKS, join(NOVEMBER, "filterDue", "no_date")).andExpect(status().isBadRequest());
        apiGet(token, TASKS, join(NOVEMBER, "projectId", work.toString(), "labelId", label.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(Texts.vi(ErrorMessage.TASK_LIST_SELECTOR)));
        apiGet(token, TASKS, join(NOVEMBER, "completed", "true", "projectId", work.toString()))
                .andExpect(status().isBadRequest());
        apiGet(token, TASKS, join(NOVEMBER, "since", "2026-11-01")).andExpect(status().isBadRequest());
        apiGet(token, TASKS, join(NOVEMBER, "filter", "p1, p2")).andExpect(status().isBadRequest());
    }

    @Test
    void fromTo_stillOnlyGoWithUpcomingOrCalendar() throws Exception {
        apiGet(token, TASKS, "projectId", work.toString(), "from", "2026-11-01").andExpect(status().isBadRequest());
        apiGet(token, TASKS, "view", "today", "from", "2026-11-01").andExpect(status().isBadRequest());
        apiGet(token, TASKS, "view", "upcoming", "from", "2026-11-05").andExpect(status().isOk());
    }

    @Test
    void calendar_otherUsersScopeIs404_andTheirTasksNeverShow() throws Exception {
        RegisteredUser other = registerUser(uniqueEmail());
        UUID theirs = data.project(other.id(), "Của B");
        UUID theirLabel = data.label(other.id(), "b");
        data.insert(TaskSpec.of(theirs, "secret").dueDate(d(5)));

        assertThat(list(NOVEMBER)).isEmpty();
        apiGet(token, TASKS, join(NOVEMBER, "projectId", theirs.toString())).andExpect(status().isNotFound());
        apiGet(token, TASKS, join(NOVEMBER, "labelId", theirLabel.toString())).andExpect(status().isNotFound());
        apiGet(token, TASKS, join(NOVEMBER, "filterLabelId", theirLabel.toString())).andExpect(status().isNotFound());
    }
}
