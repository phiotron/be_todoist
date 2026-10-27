package com.taskly.perf;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Mục tiêu spec 5.10: 10 000 task / user (trải trên nhiều dự án, mỗi dự án ≤ 300 task đang mở), một trang 50 task của
 * mỗi view < 300 ms trên máy dev.
 * Chạy riêng: .\mvnw test "-Dtest=TaskViewPerformanceTest" "-Dtaskly.perf=true"
 */
@Tag("perf")
@EnabledIfSystemProperty(named = "taskly.perf", matches = "true")
class TaskViewPerformanceTest extends IntegrationTestBase {

    private static final int PROJECTS = 40;       // 40 dự án × 250 task đang mở = 10 000
    private static final int OPEN_PER_PROJECT = 250;
    private static final int COMPLETED_PER_PROJECT = 50;
    private static final long LIMIT_MS = 300;

    @Test
    void everyViewPageUnder300ms_with10kOpenTasks() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID workspace = testData.workspaceOf(user.id()); // tasks.workspace_id NOT NULL (V2, spec 10)
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        Instant now = clock.instant();
        Timestamp created = Timestamp.from(now);
        Random random = new Random(42);
        List<UUID> projects = new ArrayList<>();
        projects.add(testData.inboxOf(user.id()));
        for (int i = 1; i < PROJECTS; i++) {
            projects.add(testData.project(user.id(), "Dự án " + i));
        }
        List<Object[]> rows = new ArrayList<>();
        for (UUID project : projects) {
            for (int i = 0; i < OPEN_PER_PROJECT + COMPLETED_PER_PROJECT; i++) {
                int kind = i % 10;
                boolean completed = i >= OPEN_PER_PROJECT;
                LocalDate dueDate = kind < 4 ? today.plusDays(random.nextInt(91) - 30) : null;
                Timestamp dueDatetime = kind == 4 || kind == 5
                        ? Timestamp.valueOf(LocalDateTime.now().plusMinutes(random.nextInt(60 * 24 * 60) - 60 * 24 * 30))
                        : null;
                Timestamp completedAt = completed ? Timestamp.from(now.minusSeconds(random.nextInt(86_400 * 30))) : null;
                rows.add(new Object[] {UUID.randomUUID(), workspace, project, user.id(), "Việc " + i, 1 + random.nextInt(4),
                        dueDate, dueDatetime, (long) (i + 1) * 1024, completedAt, created, created});
            }
        }
        jdbcTemplate.batchUpdate("insert into tasks (id, workspace_id, project_id, creator_id, content, priority, "
                + "due_date, due_datetime, sort_order, completed_at, created_at, updated_at) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", rows);
        jdbcTemplate.execute("analyze tasks");

        List<String> queries = List.of("view=today", "view=upcoming&from=" + today, "view=overdue", "view=completed",
                "view=completed&since=" + today.minusDays(7) + "&until=" + today,
                "projectId=" + projects.get(0), "projectId=" + projects.get(1) + "&completed=true");
        SoftAssertions soft = new SoftAssertions();
        for (String query : queries) {
            String uri = "/api/v1/tasks?" + query + "&limit=50";
            authGet(user.accessToken(), uri).andExpect(status().isOk()); // làm nóng
            long worst = 0;
            for (int run = 0; run < 3; run++) {
                long start = System.nanoTime();
                authGet(user.accessToken(), uri).andExpect(status().isOk());
                worst = Math.max(worst, Duration.ofNanos(System.nanoTime() - start).toMillis());
            }
            System.out.printf("[perf] %-50s chậm nhất %d ms%n", query, worst);
            soft.assertThat(worst).as(query).isLessThan(LIMIT_MS);
        }
        soft.assertAll();
    }
}
