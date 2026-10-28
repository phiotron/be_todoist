package com.taskly.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.support.TestData.TaskRow;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Insights của dự án (M10 plan F6): tiến độ + số task hoàn thành theo ngày (14 ngày, múi giờ tài khoản). */
class ProjectInsightsApiTest extends IntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-11-12T03:00:00Z"); // 10:00 ngày 12/11 giờ Việt Nam
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);

    @BeforeEach
    void freeze() {
        clock.freezeAt(NOW);
    }

    private String insights(UUID projectId) {
        return "/api/v1/projects/" + projectId + "/insights";
    }

    @Test
    void emptyProject_isZeroPercent() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "Trống");

        authGet(user.accessToken(), insights(project))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(project.toString()))
                .andExpect(jsonPath("$.totalTasks").value(0))
                .andExpect(jsonPath("$.completedTasks").value(0))
                .andExpect(jsonPath("$.activeTasks").value(0))
                .andExpect(jsonPath("$.overdueTasks").value(0))
                .andExpect(jsonPath("$.progressPercent").value(0))
                .andExpect(jsonPath("$.completedByDay", hasSize(14)))
                .andExpect(jsonPath("$.completedByDay[13].date").value("2026-11-12"))
                .andExpect(jsonPath("$.completedByDay[0].date").value("2026-10-30"));
    }

    @Test
    void countsProgressOverdueAndCompletedPerDay() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "Mẫu");
        UUID child = testData.childProject(user.id(), project, "Dự án con");
        UUID parent = testData.insert(TaskRow.of(user.id(), project, "Cha").completedAt(NOW.minusSeconds(60)));
        testData.insert(TaskRow.of(user.id(), project, "Con xong").parent(parent).completedAt(NOW.minusSeconds(60)));
        // 23:30 ngày 10/11 giờ Việt Nam (16:30 UTC) → tính vào 10/11
        testData.insert(TaskRow.of(user.id(), project, "Xong hôm kia").completedAt(Instant.parse("2026-11-10T16:30:00Z")));
        testData.insert(TaskRow.of(user.id(), project, "Quá hạn").dueDate(TODAY.minusDays(1)));
        testData.insert(TaskRow.of(user.id(), project, "Hôm nay").dueDate(TODAY));
        testData.insert(TaskRow.of(user.id(), project, "Xong lâu rồi").completedAt(NOW.minusSeconds(86_400L * 30)));
        testData.insert(TaskRow.of(user.id(), child, "Không tính (dự án con)"));

        authGet(user.accessToken(), insights(project))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTasks").value(6))
                .andExpect(jsonPath("$.completedTasks").value(4))
                .andExpect(jsonPath("$.activeTasks").value(2))
                .andExpect(jsonPath("$.overdueTasks").value(1))
                .andExpect(jsonPath("$.progressPercent").value(66))
                .andExpect(jsonPath("$.completedByDay[13].count").value(2))
                .andExpect(jsonPath("$.completedByDay[11].date").value("2026-11-10"))
                .andExpect(jsonPath("$.completedByDay[11].count").value(1))
                .andExpect(jsonPath("$.completedByDay[12].count").value(0));
    }

    @Test
    void archivedProject_isStillReadable() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "Đã lưu trữ");
        testData.insert(TaskRow.of(user.id(), project, "Một"));
        testData.archiveProject(project);

        authGet(user.accessToken(), insights(project))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTasks").value(1));
    }

    @Test
    void otherUsersProject_is404() throws Exception {
        RegisteredUser owner = registerUser(uniqueEmail());
        RegisteredUser other = registerUser(uniqueEmail());
        UUID project = testData.project(owner.id(), "Riêng");

        authGet(other.accessToken(), insights(project)).andExpect(status().isNotFound());
        authGet(owner.accessToken(), insights(UUID.randomUUID())).andExpect(status().isNotFound());
    }
}
