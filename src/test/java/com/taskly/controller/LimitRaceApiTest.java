package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.IntegrationTestBase;
import com.taskly.entity.LimitKind;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Nhiều request SONG SONG cùng muốn vượt giới hạn: nhờ SELECT … FOR UPDATE hàng cha (UsageLimits.lock*) trước khi đếm,
 * số bản ghi tạo được KHÔNG bao giờ vượt trần. Không có khoá, đếm-rồi-chèn để lọt nhiều hơn.
 */
class LimitRaceApiTest extends IntegrationTestBase {

    private static final int THREADS = 8;

    @FunctionalInterface
    private interface IndexedCall {
        int status(int index) throws Exception;
    }

    private List<Integer> parallel(IndexedCall call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            int index = i;
            futures.add(pool.submit(() -> {
                go.await();
                return call.status(index);
            }));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return statuses;
    }

    private static long count(List<Integer> statuses, int status) {
        return statuses.stream().filter(s -> s == status).count();
    }

    @Test
    void parallelTaskCreation_neverExceedsTheOpenTaskLimitOfAProject() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        usageLimits.overrideMax(LimitKind.PROJECT_ACTIVE_TASKS, 3);

        List<Integer> statuses = parallel(i -> authPost(user.accessToken(), "/api/v1/tasks",
                "{\"projectId\": \"%s\", \"content\": \"T%d\"}".formatted(project, i)).andReturn().getResponse().getStatus());

        assertThat(count(statuses, 201)).isEqualTo(3);
        assertThat(count(statuses, 422)).isEqualTo(THREADS - 3);
        assertThat(jdbcTemplate.queryForObject("select count(*) from tasks where project_id = ? and completed_at is null",
                Integer.class, project)).isEqualTo(3);
    }

    @Test
    void parallelSectionCreation_neverExceedsTheActiveSectionLimit() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        usageLimits.overrideMax(LimitKind.PROJECT_SECTIONS, 2);

        List<Integer> statuses = parallel(i -> authPost(user.accessToken(), "/api/v1/projects/" + project + "/sections",
                "{\"name\": \"S%d\"}".formatted(i)).andReturn().getResponse().getStatus());

        assertThat(count(statuses, 201)).isEqualTo(2);
        assertThat(count(statuses, 422)).isEqualTo(THREADS - 2);
        assertThat(jdbcTemplate.queryForObject("select count(*) from sections where project_id = ?", Integer.class, project))
                .isEqualTo(2);
    }

    @Test
    void parallelProjectCreation_neverExceedsTheActiveProjectLimit() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        usageLimits.overrideMax(LimitKind.ACTIVE_PROJECTS, 2);

        List<Integer> statuses = parallel(i -> authPost(user.accessToken(), "/api/v1/projects",
                "{\"name\": \"P%d\"}".formatted(i)).andReturn().getResponse().getStatus());

        assertThat(count(statuses, 201)).isEqualTo(2);
        assertThat(count(statuses, 422)).isEqualTo(THREADS - 2);
    }
}
