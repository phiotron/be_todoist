package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.IntegrationTestBase;
import com.taskly.support.TestData.TaskRow;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Đua THẬT không xác định: nhiều luồng song song trên cùng một dự án, trộn create / close / reopen / move / reorder /
 * lưu trữ section / lưu trữ dự án đích. Khẳng định bất biến cuối: Postgres không báo deadlock nào (pg_stat_database),
 * không request nào trả 5xx, không task mở trong section đã lưu trữ (lưu trữ dự án KHÔNG hoàn thành task nên không có bất biến tương ứng), task con cùng dự án (và section) với cha,
 * sort_order không trùng trong một vùng chứa. Lặp 5 lần (fixture mới mỗi lần) để tăng xác suất lộ lỗi.
 */
class ConcurrentMixedOperationsApiTest extends IntegrationTestBase {

    private static final int THREADS = 12;
    private static final int OPS_PER_THREAD = 20;

    private long deadlocks() {
        Long value = jdbcTemplate.queryForObject(
                "select deadlocks from pg_stat_database where datname = current_database()", Long.class);
        return value == null ? 0 : value;
    }

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    @RepeatedTest(5)
    void mixedOperationsFromManyThreads_keepInvariants() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p = testData.project(user.id(), "P");
        UUID q = testData.project(user.id(), "Q");
        List<UUID> sections = List.of(testData.section(p, "S1"), testData.section(p, "S2"));
        List<UUID> tasks = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            UUID root = testData.task(user.id(), p, "R" + i);
            if (i % 3 != 0) {
                jdbcTemplate.update("update tasks set section_id = ? where id = ?", sections.get(i % 2), root);
            }
            tasks.add(root);
            if (i % 3 == 0) {
                tasks.add(testData.insert(TaskRow.of(user.id(), p, "C" + i).parent(root)));
            }
        }
        List<UUID> projects = List.of(p, q);
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        long deadlocksBefore = deadlocks();
        String token = user.accessToken();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            futures.add(pool.submit(() -> {
                go.await();
                ThreadLocalRandom r = ThreadLocalRandom.current();
                for (int i = 0; i < OPS_PER_THREAD; i++) {
                    UUID task = tasks.get(r.nextInt(tasks.size()));
                    UUID other = tasks.get(r.nextInt(tasks.size()));
                    UUID section = sections.get(r.nextInt(2));
                    UUID project = projects.get(r.nextInt(2));
                    String label;
                    ResultActions call;
                    switch (r.nextInt(9)) {
                        case 0 -> {
                            label = "create";
                            call = authPost(token, "/api/v1/tasks",
                                    "{\"projectId\": \"%s\", \"sectionId\": \"%s\", \"content\": \"N\"}".formatted(p, section));
                        }
                        case 1 -> {
                            label = "createSub";
                            call = authPost(token, "/api/v1/tasks", "{\"parentId\": \"%s\", \"content\": \"Con\"}".formatted(task));
                        }
                        case 2 -> {
                            label = "close";
                            call = authPost(token, "/api/v1/tasks/" + task + "/close");
                        }
                        case 3 -> {
                            label = "reopen";
                            call = authPost(token, "/api/v1/tasks/" + task + "/reopen");
                        }
                        case 4 -> {
                            label = "move";
                            call = authPost(token, "/api/v1/tasks/" + task + "/move", project.equals(p)
                                    ? "{\"projectId\": \"%s\", \"sectionId\": \"%s\"}".formatted(p, section)
                                    : "{\"projectId\": \"%s\"}".formatted(q));
                        }
                        case 5 -> {
                            label = "reorder";
                            call = authPost(token, "/api/v1/reorder",
                                    "{\"type\": \"TASK\", \"id\": \"%s\", \"afterId\": \"%s\"}".formatted(task, other));
                        }
                        case 6 -> {
                            label = "archiveSection";
                            call = authPost(token, "/api/v1/projects/" + p + "/sections/" + section + "/archive");
                        }
                        case 7 -> {
                            label = "unarchiveSection";
                            call = authPost(token, "/api/v1/projects/" + p + "/sections/" + section + "/unarchive");
                        }
                        default -> {
                            boolean archive = r.nextBoolean();
                            label = archive ? "archiveProject" : "unarchiveProject";
                            call = authPost(token, "/api/v1/projects/" + q + (archive ? "/archive" : "/unarchive"));
                        }
                    }
                    var response = call.andReturn().getResponse();
                    if (response.getStatus() >= 500) {
                        failures.add(label + " -> " + response.getStatus() + " "
                                + response.getContentAsString(StandardCharsets.UTF_8));
                    }
                }
                return null;
            }));
        }
        go.countDown();
        try {
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(failures).as("request trả 5xx").isEmpty();
        assertThat(deadlocks() - deadlocksBefore).as("deadlock Postgres đã ghi nhận").isZero();
        assertThat(count("select count(*) from tasks t join sections s on s.id = t.section_id "
                + "where s.archived_at is not null and t.completed_at is null and t.project_id = ?", p))
                .as("task mở trong section đã lưu trữ").isZero();
        assertThat(count("select count(*) from tasks c join tasks pr on pr.id = c.parent_id "
                + "where c.project_id <> pr.project_id and c.project_id in (?, ?)", p, q))
                .as("con lệch project_id với cha").isZero();
        assertThat(count("select count(*) from tasks c join tasks pr on pr.id = c.parent_id "
                + "where c.section_id is distinct from pr.section_id and c.project_id in (?, ?)", p, q))
                .as("con lệch section_id với cha").isZero();
        assertThat(count("select count(*) from (select 1 from tasks where project_id in (?, ?) "
                + "group by project_id, section_id, parent_id, sort_order having count(*) > 1) d", p, q))
                .as("sort_order trùng trong một vùng chứa").isZero();
    }
}
