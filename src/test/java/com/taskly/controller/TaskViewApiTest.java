package com.taskly.controller;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.IntegrationTestBase;
import com.taskly.support.TestData.TaskRow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Giờ đóng băng 2026-10-23T16:59:00Z = 23:59 ngày 23/10 ở Asia/Ho_Chi_Minh (múi giờ mặc định của tài khoản).
 * A: ngày 23 · B: ngày 24 · C: 16:00 ngày 23 (đã qua) · D: 23:59:30 ngày 23 (chưa tới) · E: ngày 22 · F: không due
 * G: ngày 23 đã xong. Due có giờ là FLOATING: so với giờ treo tường của tài khoản.
 */
class TaskViewApiTest extends IntegrationTestBase {

    private static final String TASKS = "/api/v1/tasks";

    private RegisteredUser user;
    private UUID project;

    @BeforeEach
    void data() throws Exception {
        clock.freezeAt(Instant.parse("2026-10-23T16:59:00Z"));
        user = registerUser(uniqueEmail());
        project = testData.project(user.id(), "Việc");
        testData.insert(TaskRow.of(user.id(), project, "A").dueDate(LocalDate.of(2026, 10, 23)));
        testData.insert(TaskRow.of(user.id(), project, "B").dueDate(LocalDate.of(2026, 10, 24)));
        testData.insert(TaskRow.of(user.id(), project, "C").dueDatetime(LocalDateTime.of(2026, 10, 23, 16, 0)));
        testData.insert(TaskRow.of(user.id(), project, "D").dueDatetime(LocalDateTime.of(2026, 10, 23, 23, 59, 30)));
        testData.insert(TaskRow.of(user.id(), project, "E").dueDate(LocalDate.of(2026, 10, 22)));
        testData.task(user.id(), project, "F");
        testData.insert(TaskRow.of(user.id(), project, "G").dueDate(LocalDate.of(2026, 10, 23))
                .completedAt(Instant.parse("2026-10-23T10:00:00Z")));
    }

    private String loginToken(String email) throws Exception {
        String json = body(mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\", \"password\": \"%s\"}".formatted(email, FIXTURE_PASSWORD)))
                .andExpect(status().isOk()).andReturn());
        return JsonPath.read(json, "$.accessToken");
    }

    @Test
    void today_overdueFirstThenByDue_beforeMidnight() throws Exception {
        authGet(user.accessToken(), TASKS + "?view=today")
                .andExpect(status().isOk())
                // Khối Quá hạn (due_day < hôm nay) = E. Khối Hôm nay theo due_sort: A (00:00), C (16:00 — đã qua nhưng
                // vẫn trong hôm nay → ở khối Hôm nay, isOverdue = true chỉ để tô đỏ), D (23:59:30).
                .andExpect(jsonPath("$.items[*].content", contains("E", "A", "C", "D")))
                .andExpect(jsonPath("$.items[*].isOverdue", contains(true, false, true, false)))
                .andExpect(jsonPath("$.items[0].projectName").value("Việc"))
                .andExpect(jsonPath("$.items[1].dueDatetime").doesNotExist())
                .andExpect(jsonPath("$.items[3].dueDatetime").value("2026-10-23T23:59:30"))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void today_afterMidnight_yesterdaysDateTaskBecomesOverdue() throws Exception {
        // Chỉ dời 2 phút nên access token (15 phút) còn hạn.
        clock.freezeAt(Instant.parse("2026-10-23T17:01:00Z")); // 00:01 ngày 24/10

        authGet(user.accessToken(), TASKS + "?view=today")
                .andExpect(jsonPath("$.items[*].content", contains("E", "A", "C", "D", "B")))
                .andExpect(jsonPath("$.items[*].isOverdue", contains(true, true, true, true, false)));
    }

    @Test
    void overdue_andUpcoming() throws Exception {
        // Khối Quá hạn chỉ có due_day < hôm nay: C (16:00 hôm nay, đã qua) KHÔNG nằm ở đây (spec 5.1, C3).
        authGet(user.accessToken(), TASKS + "?view=overdue")
                .andExpect(jsonPath("$.items[*].content", contains("E")))
                .andExpect(jsonPath("$.items[0].isOverdue").value(true));
        authGet(user.accessToken(), TASKS + "?view=upcoming&from=2026-10-23")
                .andExpect(jsonPath("$.items[*].content", contains("A", "C", "D", "B")));
        authGet(user.accessToken(), TASKS + "?view=upcoming&from=2026-10-24")
                .andExpect(jsonPath("$.items[*].content", contains("B")));
        authGet(user.accessToken(), TASKS + "?view=upcoming&from=2026-10-20") // from < hôm nay → tính từ hôm nay
                .andExpect(jsonPath("$.items[*].content", contains("A", "C", "D", "B")));
    }

    @Test
    void floatingDue_isComparedWithAccountWallClock_notWithUtc() throws Exception {
        // 2026-10-24T02:00Z = 09:00 ngày 24 ở Việt Nam nhưng 22:00 ngày 23 ở New York. Token cũ đã hết hạn (clock dời
        // 9 giờ) → đăng nhập lại SAU khi dời giờ, một lần duy nhất.
        clock.freezeAt(Instant.parse("2026-10-24T02:00:00Z"));
        String token = loginToken(user.email());

        authGet(token, TASKS + "?view=overdue")
                .andExpect(jsonPath("$.items[*].content", contains("E", "A", "C", "D")));

        testData.setTimeZone(user.id(), "America/New_York");
        authGet(token, TASKS + "?view=overdue")
                .andExpect(jsonPath("$.items[*].content", contains("E"))); // 23/10 ở New York: A, C, D còn ở khối Hôm nay
        authGet(token, TASKS + "?view=today")
                .andExpect(jsonPath("$.items[*].content", contains("E", "A", "C", "D")))
                .andExpect(jsonPath("$.items[*].isOverdue", contains(true, false, true, false))) // C 16:00 < 22:00 → đỏ; D chưa tới
                .andExpect(jsonPath("$.items[3].dueDatetime").value("2026-10-23T23:59:30")); // giờ không đổi theo múi giờ
    }

    @Test
    void sameDayTieBreak_priorityThenProjectOrderThenTaskOrder() throws Exception {
        UUID second = testData.project(user.id(), "Thứ hai"); // sort_order lớn hơn dự án "Việc"
        LocalDate day = LocalDate.of(2026, 10, 25);
        testData.insert(TaskRow.of(user.id(), second, "p4-dự án-2").dueDate(day));
        testData.insert(TaskRow.of(user.id(), project, "p4-sau").dueDate(day).sortOrder(9_000_000_000L));
        testData.insert(TaskRow.of(user.id(), project, "p4-trước").dueDate(day).sortOrder(1));
        testData.insert(TaskRow.of(user.id(), second, "p1").dueDate(day).priority(1));

        authGet(user.accessToken(), TASKS + "?view=upcoming&from=2026-10-25")
                .andExpect(jsonPath("$.items[*].content", contains("p1", "p4-trước", "p4-sau", "p4-dự án-2")));
    }

    @Test
    void archivedProject_isHiddenFromViews_andSubtaskShowsParentName() throws Exception {
        UUID parent = testData.task(user.id(), project, "Cha không hạn");
        testData.insert(TaskRow.of(user.id(), project, "Con có hạn").parent(parent)
                .dueDate(LocalDate.of(2026, 10, 23)));
        authGet(user.accessToken(), TASKS + "?view=today")
                .andExpect(jsonPath("$.items[?(@.content == 'Con có hạn')].parentContent",
                        contains("Cha không hạn")));

        testData.archiveProject(project);
        authGet(user.accessToken(), TASKS + "?view=today").andExpect(jsonPath("$.items", empty()));
        authGet(user.accessToken(), TASKS + "?view=overdue").andExpect(jsonPath("$.items", empty()));
        authGet(user.accessToken(), TASKS + "?view=upcoming&from=2026-10-23").andExpect(jsonPath("$.items", empty()));
    }

    @Test
    void completedTasks_neverAppearInViews_andOtherUserSeesNothing() throws Exception {
        authGet(user.accessToken(), TASKS + "?view=today")
                .andExpect(jsonPath("$.items[?(@.content == 'G')]", empty()));

        RegisteredUser bob = registerUser(uniqueEmail());
        authGet(bob.accessToken(), TASKS + "?view=today").andExpect(jsonPath("$.items", empty()));
        authGet(bob.accessToken(), TASKS + "?view=overdue").andExpect(jsonPath("$.items", empty()));
        authGet(bob.accessToken(), TASKS + "?projectId=" + project).andExpect(status().isNotFound());
    }
}
