package com.taskly.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.support.TestData.TaskRow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * GET tasks/day-counts (M6b, contract 8.2): số task ĐANG MỞ theo due_day trong [from, to] (≤ 62 ngày), chỉ dự án đọc
 * được, bỏ dự án lưu trữ; mọi ngày trong khoảng đều có mặt (0 nếu không có task). Dùng cho lịch của Date sheet.
 */
class TaskDayCountsApiTest extends IntegrationTestBase {

    private static final String COUNTS = "/api/v1/tasks/day-counts";
    private static final String RANGE_DETAIL = "Khoảng ngày phải có from ≤ to và không quá 62 ngày";

    private RegisteredUser user;
    private UUID project;

    @BeforeEach
    void data() throws Exception {
        clock.freezeAt(Instant.parse("2026-10-27T03:00:00Z"));
        user = registerUser(uniqueEmail());
        project = testData.project(user.id(), "Work");
    }

    @Test
    void countsOpenTasksPerDueDay_includingInboxSubtasksAndTimedTasks() throws Exception {
        LocalDate day = LocalDate.of(2026, 10, 28);
        UUID parent = testData.insert(TaskRow.of(user.id(), project, "a").dueDate(day));
        testData.insert(TaskRow.of(user.id(), project, "a.1").parent(parent).dueDate(day));
        testData.insert(TaskRow.of(user.id(), testData.inboxOf(user.id()), "inbox").dueDate(day));
        testData.insert(TaskRow.of(user.id(), project, "timed").dueDatetime(LocalDateTime.of(2026, 10, 29, 9, 0)));
        testData.insert(TaskRow.of(user.id(), project, "no due"));

        authGet(user.accessToken(), COUNTS + "?from=2026-10-27&to=2026-10-30")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.length()").value(4))
                .andExpect(jsonPath("$.counts['2026-10-27']").value(0))
                .andExpect(jsonPath("$.counts['2026-10-28']").value(3))
                .andExpect(jsonPath("$.counts['2026-10-29']").value(1))
                .andExpect(jsonPath("$.counts['2026-10-30']").value(0));
    }

    @Test
    void ignoresCompletedTasksArchivedProjectsOutsideTheRangeAndOtherUsers() throws Exception {
        LocalDate day = LocalDate.of(2026, 11, 2);
        testData.insert(TaskRow.of(user.id(), project, "open").dueDate(day));
        testData.insert(TaskRow.of(user.id(), project, "done").dueDate(day)
                .completedAt(Instant.parse("2026-10-26T01:00:00Z")));
        UUID archived = testData.project(user.id(), "Old");
        testData.insert(TaskRow.of(user.id(), archived, "archived").dueDate(day));
        testData.archiveProject(archived);
        testData.insert(TaskRow.of(user.id(), project, "before").dueDate(LocalDate.of(2026, 11, 1)));
        testData.insert(TaskRow.of(user.id(), project, "after").dueDate(LocalDate.of(2026, 11, 3)));
        RegisteredUser other = registerUser(uniqueEmail());
        testData.insert(TaskRow.of(other.id(), testData.project(other.id(), "Theirs"), "theirs").dueDate(day));

        authGet(user.accessToken(), COUNTS + "?from=2026-11-02&to=2026-11-02")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.length()").value(1))
                .andExpect(jsonPath("$.counts['2026-11-02']").value(1));
    }

    @Test
    void pastDaysAreCountedToo() throws Exception {
        testData.insert(TaskRow.of(user.id(), project, "late").dueDate(LocalDate.of(2026, 10, 20)));

        authGet(user.accessToken(), COUNTS + "?from=2026-10-20&to=2026-10-20")
                .andExpect(jsonPath("$.counts['2026-10-20']").value(1));
    }

    @Test
    void sixtyTwoDaysAreAllowed_sixtyThreeAre400() throws Exception {
        authGet(user.accessToken(), COUNTS + "?from=2026-11-01&to=2027-01-01") // 62 ngày (to − from = 61)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.length()").value(62));
        authGet(user.accessToken(), COUNTS + "?from=2026-11-01&to=2027-01-02")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(RANGE_DETAIL));
    }

    @Test
    void toBeforeFrom_missingOrMalformedParameters_are400() throws Exception {
        authGet(user.accessToken(), COUNTS + "?from=2026-11-02&to=2026-11-01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(RANGE_DETAIL));
        authGet(user.accessToken(), COUNTS + "?from=2026-11-02").andExpect(status().isBadRequest());
        authGet(user.accessToken(), COUNTS + "?from=02/11/2026&to=2026-11-03").andExpect(status().isBadRequest());
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get(COUNTS + "?from=2026-11-01&to=2026-11-02")).andExpect(status().isUnauthorized());
    }
}
