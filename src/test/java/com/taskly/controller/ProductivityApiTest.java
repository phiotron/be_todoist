package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.security.JwtTokenIssuer;
import com.taskly.support.TestData.TaskRow;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/**
 * GET /api/v1/productivity (M10 plan F2–F4): mục tiêu ngày / tuần, streak, ngày nghỉ, Vacation mode, Karma (điểm, cấp,
 * trend, phạt quá hạn). Đồng hồ đóng băng ở 2026-11-12 10:00 giờ Việt Nam (Thứ Năm).
 */
class ProductivityApiTest extends IntegrationTestBase {

    private static final String PRODUCTIVITY = "/api/v1/productivity";
    private static final String SETTINGS = "/api/v1/me/settings";
    private static final Instant NOW = Instant.parse("2026-11-12T03:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);

    @Autowired
    private JwtTokenIssuer tokens;

    @BeforeEach
    void freeze() {
        clock.freezeAt(NOW);
    }

    // ---- helper ----

    private UUID task(RegisteredUser user, String content) throws Exception {
        return readUuid(authPost(user.accessToken(), "/api/v1/tasks", """
                {"content": "%s"}
                """.formatted(content)).andExpect(status().isCreated()), "$.id");
    }

    private void close(RegisteredUser user, UUID taskId) throws Exception {
        authPost(user.accessToken(), "/api/v1/tasks/" + taskId + "/close").andExpect(status().isOk());
    }

    private void completeNew(RegisteredUser user, int count) throws Exception {
        for (int i = 0; i < count; i++) {
            close(user, task(user, "Việc " + i));
        }
    }

    private void settings(RegisteredUser user, String json) throws Exception {
        authPatch(user.accessToken(), SETTINGS, json).andExpect(status().isOk());
    }

    private ResultActions productivity(RegisteredUser user) throws Exception {
        return authGet(tokens.issueAccessToken(user.id()), PRODUCTIVITY).andExpect(status().isOk());
    }

    /** Lần cộng Karma cho một lần hoàn thành ở ngày day (dữ liệu lịch sử, không qua API). */
    private void pastCompletion(UUID userId, LocalDate day) {
        jdbcTemplate.update("insert into karma_events (id, user_id, reason, points, task_id, occurred_on, created_at) "
                + "values (?, ?, 'TASK_COMPLETED', 5, ?, ?, ?)", UUID.randomUUID(), userId, UUID.randomUUID(), day,
                Timestamp.from(NOW));
    }

    private int karmaRows(UUID userId, String reason) {
        return jdbcTemplate.queryForObject("select count(*) from karma_events where user_id = ? and reason = ?",
                Integer.class, userId, reason);
    }

    // ---- tài khoản mới ----

    @Test
    void newAccount_startsAtZero() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        productivity(user)
                .andExpect(jsonPath("$.today").value("2026-11-12"))
                .andExpect(jsonPath("$.dailyGoal").value(5))
                .andExpect(jsonPath("$.weeklyGoal").value(30))
                .andExpect(jsonPath("$.karmaEnabled").value(true))
                .andExpect(jsonPath("$.goalCelebrations").value(true))
                .andExpect(jsonPath("$.vacationMode").value(false))
                .andExpect(jsonPath("$.daily.completed").value(0))
                .andExpect(jsonPath("$.daily.goal").value(5))
                .andExpect(jsonPath("$.daily.reached").value(false))
                .andExpect(jsonPath("$.daily.currentStreak").value(0))
                .andExpect(jsonPath("$.daily.longestStreak").value(0))
                .andExpect(jsonPath("$.daily.days", hasSize(7)))
                .andExpect(jsonPath("$.daily.days[6].date").value("2026-11-12"))
                .andExpect(jsonPath("$.daily.days[0].date").value("2026-11-06"))
                .andExpect(jsonPath("$.daily.days[1].dayOff").value(true)) // Thứ Bảy 07/11
                .andExpect(jsonPath("$.daily.days[6].dayOff").value(false))
                .andExpect(jsonPath("$.weekly.completed").value(0))
                .andExpect(jsonPath("$.weekly.goal").value(30))
                .andExpect(jsonPath("$.weekly.weekStart").value("2026-11-09"))
                .andExpect(jsonPath("$.weekly.weeks", hasSize(4)))
                .andExpect(jsonPath("$.weekly.weeks[3].weekStart").value("2026-11-09"))
                .andExpect(jsonPath("$.karma.karma").value(0))
                .andExpect(jsonPath("$.karma.level").value("BEGINNER"))
                .andExpect(jsonPath("$.karma.nextLevel").value("NOVICE"))
                .andExpect(jsonPath("$.karma.nextLevelKarma").value(500))
                .andExpect(jsonPath("$.karma.pointsToNextLevel").value(500))
                .andExpect(jsonPath("$.karma.trend", hasSize(28)))
                .andExpect(jsonPath("$.karma.levels", hasSize(8)))
                .andExpect(jsonPath("$.karma.levels[7].level").value("ENLIGHTENED"))
                .andExpect(jsonPath("$.karma.levels[7].minKarma").value(50000));
    }

    // ---- Karma cộng / trừ ----

    @Test
    void completingTasks_addsKarmaAndCountsToday() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        completeNew(user, 2);

        productivity(user)
                .andExpect(jsonPath("$.daily.completed").value(2))
                .andExpect(jsonPath("$.daily.days[6].completed").value(2))
                .andExpect(jsonPath("$.weekly.completed").value(2))
                .andExpect(jsonPath("$.karma.karma").value(10))
                .andExpect(jsonPath("$.karma.trend[27].date").value("2026-11-12"))
                .andExpect(jsonPath("$.karma.trend[27].karma").value(10))
                .andExpect(jsonPath("$.karma.trend[26].karma").value(0));
    }

    @Test
    void reopening_takesBackTheKarmaAndTheCount() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID a = task(user, "A");
        UUID b = task(user, "B");
        close(user, a);
        close(user, b);

        authPost(user.accessToken(), "/api/v1/tasks/" + a + "/reopen", "{\"undo\": true}").andExpect(status().isOk());
        // mở lại lần nữa (đang mở) → không trừ thêm
        authPost(user.accessToken(), "/api/v1/tasks/" + a + "/reopen").andExpect(status().isOk());

        productivity(user)
                .andExpect(jsonPath("$.daily.completed").value(1))
                .andExpect(jsonPath("$.karma.karma").value(5));
        // hoàn thành lại → cộng lại
        close(user, a);
        productivity(user).andExpect(jsonPath("$.karma.karma").value(10));
    }

    @Test
    void reopeningATaskCompletedBeforeM10_takesNothing() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID old = testData.insert(TaskRow.of(user.id(), testData.inboxOf(user.id()), "Xong từ trước")
                .completedAt(NOW.minusSeconds(3600)));

        authPost(user.accessToken(), "/api/v1/tasks/" + old + "/reopen").andExpect(status().isOk());

        assertThat(karmaRows(user.id(), "TASK_UNCOMPLETED")).isZero();
        productivity(user).andExpect(jsonPath("$.karma.karma").value(0)).andExpect(jsonPath("$.daily.completed").value(0));
    }

    @Test
    void subtasksCompletedWithTheParent_countEach() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID parent = task(user, "Cha");
        authPost(user.accessToken(), "/api/v1/tasks", """
                {"parentId": "%s", "content": "Con"}
                """.formatted(parent)).andExpect(status().isCreated());

        close(user, parent);

        productivity(user).andExpect(jsonPath("$.daily.completed").value(2)).andExpect(jsonPath("$.karma.karma").value(10));
    }

    @Test
    void batchClose_countsEveryTask() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID a = task(user, "A");
        UUID b = task(user, "B");

        authPost(user.accessToken(), "/api/v1/tasks/batch", """
                {"action": "CLOSE", "taskIds": ["%s", "%s"]}
                """.formatted(a, b)).andExpect(status().isOk());

        productivity(user).andExpect(jsonPath("$.daily.completed").value(2));
    }

    @Test
    void archivingASection_doesNotAwardKarma() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "Dự án");
        UUID section = testData.section(project, "Section");
        testData.insert(TaskRow.of(user.id(), project, "Trong section").section(section));

        authPost(user.accessToken(), "/api/v1/projects/" + project + "/sections/" + section + "/archive")
                .andExpect(status().isOk());

        productivity(user).andExpect(jsonPath("$.daily.completed").value(0)).andExpect(jsonPath("$.karma.karma").value(0));
    }

    // ---- mục tiêu ----

    @Test
    void reachingTheDailyGoal_awardsTheBonusOncePerDay() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        settings(user, "{\"dailyGoal\": 2}");

        completeNew(user, 3);

        assertThat(karmaRows(user.id(), "DAILY_GOAL")).isEqualTo(1);
        productivity(user)
                .andExpect(jsonPath("$.daily.reached").value(true))
                .andExpect(jsonPath("$.daily.currentStreak").value(1))
                .andExpect(jsonPath("$.karma.karma").value(3 * 5 + 10));
    }

    @Test
    void reachingTheWeeklyGoal_awardsTheBonusOncePerWeek() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        settings(user, "{\"dailyGoal\": 10, \"weeklyGoal\": 3}");
        pastCompletion(user.id(), TODAY.minusDays(2)); // Thứ Ba cùng tuần

        completeNew(user, 3);

        assertThat(karmaRows(user.id(), "WEEKLY_GOAL")).isEqualTo(1);
        productivity(user)
                .andExpect(jsonPath("$.weekly.completed").value(4))
                .andExpect(jsonPath("$.weekly.reached").value(true))
                .andExpect(jsonPath("$.weekly.currentStreak").value(1))
                .andExpect(jsonPath("$.karma.karma").value(4 * 5 + 30));
    }

    // ---- streak, ngày nghỉ, vacation ----

    @Test
    void dailyStreak_skipsDaysOff() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        settings(user, "{\"dailyGoal\": 1, \"daysOff\": [2]}"); // Thứ Ba 10/11 nghỉ
        pastCompletion(user.id(), TODAY.minusDays(3)); // Thứ Hai 09/11
        pastCompletion(user.id(), TODAY.minusDays(1)); // Thứ Tư 11/11

        productivity(user)
                .andExpect(jsonPath("$.daily.currentStreak").value(2)) // hôm nay chưa đạt → giữ streak tới hôm qua
                .andExpect(jsonPath("$.daily.longestStreak").value(2))
                .andExpect(jsonPath("$.daily.days[4].dayOff").value(true));
        completeNew(user, 1);
        productivity(user).andExpect(jsonPath("$.daily.currentStreak").value(3));
    }

    @Test
    void dailyStreak_brokenByAMissedWorkday() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        settings(user, "{\"dailyGoal\": 1, \"daysOff\": []}");
        pastCompletion(user.id(), TODAY.minusDays(3));
        pastCompletion(user.id(), TODAY.minusDays(4));

        productivity(user)
                .andExpect(jsonPath("$.daily.currentStreak").value(0))
                .andExpect(jsonPath("$.daily.longestStreak").value(2));
    }

    @Test
    void vacationDays_doNotBreakTheStreak() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        settings(user, "{\"dailyGoal\": 1, \"daysOff\": []}");
        pastCompletion(user.id(), TODAY.minusDays(4));
        jdbcTemplate.update("insert into vacation_periods (id, user_id, started_on, ended_on, created_at) "
                        + "values (?, ?, ?, ?, ?)", UUID.randomUUID(), user.id(), TODAY.minusDays(3),
                TODAY.minusDays(1), Timestamp.from(NOW));

        productivity(user)
                .andExpect(jsonPath("$.daily.currentStreak").value(1))
                .andExpect(jsonPath("$.daily.days[5].vacation").value(true));
    }

    // ---- phạt quá hạn ----

    @Test
    void overdueByFiveDays_costsKarmaOnce_fourDaysDoesNot() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        completeNew(user, 2); // +10
        UUID inbox = testData.inboxOf(user.id());
        UUID late = testData.insert(TaskRow.of(user.id(), inbox, "Trễ 5 ngày").dueDate(TODAY.minusDays(5)));
        testData.insert(TaskRow.of(user.id(), inbox, "Trễ 4 ngày").dueDate(TODAY.minusDays(4)));
        testData.insert(TaskRow.of(user.id(), inbox, "Xong rồi").dueDate(TODAY.minusDays(9)).completedAt(NOW));

        productivity(user).andExpect(jsonPath("$.karma.karma").value(8));
        productivity(user).andExpect(jsonPath("$.karma.karma").value(8)); // đọc lại không trừ thêm

        assertThat(jdbcTemplate.queryForObject("select occurred_on from karma_events where task_id = ? "
                + "and reason = 'OVERDUE_PENALTY'", LocalDate.class, late)).isEqualTo(TODAY);
    }

    @Test
    void overduePenalty_skippedDuringVacation_andKarmaNeverBelowZero() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID inbox = testData.inboxOf(user.id());
        testData.insert(TaskRow.of(user.id(), inbox, "Trễ lâu").dueDate(TODAY.minusDays(30)));
        settings(user, "{\"vacationMode\": true}");
        UUID duringVacation = testData.insert(TaskRow.of(user.id(), inbox, "Trễ trong kỳ nghỉ")
                .dueDate(TODAY.minusDays(5)));

        productivity(user).andExpect(jsonPath("$.karma.karma").value(0)).andExpect(jsonPath("$.vacationMode").value(true));

        assertThat(karmaRows(user.id(), "OVERDUE_PENALTY")).isEqualTo(1); // chỉ task trễ trước kỳ nghỉ
        assertThat(jdbcTemplate.queryForObject("select count(*) from karma_events where task_id = ?",
                Integer.class, duringVacation)).isZero();
    }

    @Test
    void overduePenalty_ignoresArchivedProjectsAndOtherPeoplesTasks() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID archived = testData.project(user.id(), "Lưu trữ");
        testData.insert(TaskRow.of(user.id(), archived, "Trong dự án lưu trữ").dueDate(TODAY.minusDays(10)));
        testData.archiveProject(archived);

        productivity(user);

        assertThat(karmaRows(user.id(), "OVERDUE_PENALTY")).isZero();
    }

    // ---- hiển thị ----

    @Test
    void karmaDisabled_hidesKarmaButKeepsGoals() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        settings(user, "{\"karmaEnabled\": false}");
        completeNew(user, 1);

        productivity(user)
                .andExpect(jsonPath("$.karmaEnabled").value(false))
                .andExpect(jsonPath("$.karma").value(nullValue()))
                .andExpect(jsonPath("$.daily.completed").value(1));
        assertThat(karmaRows(user.id(), "TASK_COMPLETED")).isEqualTo(1); // vẫn ghi để bật lại không mất lịch sử
    }

    @Test
    void completionDay_followsTheAccountTimeZone() throws Exception {
        clock.freezeAt(Instant.parse("2026-11-12T18:30:00Z")); // 01:30 ngày 13/11 giờ Việt Nam; 18:30 ngày 12/11 UTC
        RegisteredUser user = registerUser(uniqueEmail());
        completeNew(user, 1);

        productivity(user)
                .andExpect(jsonPath("$.today").value("2026-11-13"))
                .andExpect(jsonPath("$.daily.completed").value(1));

        testData.setTimeZone(user.id(), "UTC");
        productivity(user)
                .andExpect(jsonPath("$.today").value("2026-11-12"))
                .andExpect(jsonPath("$.daily.completed").value(0)); // lần cộng ghi theo ngày lúc hoàn thành (13/11)
    }

    @Test
    void karmaTrend_isCumulativePerDay() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        pastCompletion(user.id(), TODAY.minusDays(40)); // trước cửa sổ 28 ngày → vào mức nền
        pastCompletion(user.id(), TODAY.minusDays(10));
        pastCompletion(user.id(), TODAY.minusDays(10));

        productivity(user)
                .andExpect(jsonPath("$.karma.karma").value(15))
                .andExpect(jsonPath("$.karma.trend[0].date").value("2026-10-16"))
                .andExpect(jsonPath("$.karma.trend[0].karma").value(5))
                .andExpect(jsonPath("$.karma.trend[16].karma").value(5))
                .andExpect(jsonPath("$.karma.trend[17].date").value("2026-11-02"))
                .andExpect(jsonPath("$.karma.trend[17].karma").value(15))
                .andExpect(jsonPath("$.karma.trend[27].karma").value(15));
    }

    @Test
    void levelAndPointsToNextLevel() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        jdbcTemplate.update("insert into karma_events (id, user_id, reason, points, occurred_on, created_at) "
                        + "values (?, ?, 'DAILY_GOAL', 223, ?, ?)", UUID.randomUUID(), user.id(), TODAY.minusDays(1),
                Timestamp.from(NOW));

        productivity(user)
                .andExpect(jsonPath("$.karma.karma").value(223))
                .andExpect(jsonPath("$.karma.level").value("BEGINNER"))
                .andExpect(jsonPath("$.karma.pointsToNextLevel").value(277));
    }
}
