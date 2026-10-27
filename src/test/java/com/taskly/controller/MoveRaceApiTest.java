package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.IntegrationTestBase;
import com.taskly.support.TestData.TaskRow;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * Đua hai move ngược chiều ("A con B" và "B con A") không được tạo vòng lặp: move khoá dự án (task) / workspace (dự án)
 * TRƯỚC khi dựng cây (spec 8.2 #9) → đúng một thành công, bên kia thấy cây mới và bị 400.
 */
class MoveRaceApiTest extends IntegrationTestBase {

    private static final int ROUNDS = 8;

    private List<Integer> runInParallel(List<Callable<Integer>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CyclicBarrier barrier = new CyclicBarrier(calls.size());
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> call : calls) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    return call.call();
                }));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get());
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void oppositeTaskMovesInParallel_exactlyOneSucceeds_noCycle() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        for (int round = 0; round < ROUNDS; round++) {
            UUID project = testData.project(user.id(), "P" + round);
            UUID a = testData.insert(TaskRow.of(user.id(), project, "A"));
            UUID b = testData.insert(TaskRow.of(user.id(), project, "B"));
            String bodyAUnderB = "{\"projectId\": \"%s\", \"parentId\": \"%s\"}".formatted(project, b);
            String bodyBUnderA = "{\"projectId\": \"%s\", \"parentId\": \"%s\"}".formatted(project, a);

            List<Integer> statuses = runInParallel(List.of(
                    () -> authPost(user.accessToken(), "/api/v1/tasks/" + a + "/move", bodyAUnderB)
                            .andReturn().getResponse().getStatus(),
                    () -> authPost(user.accessToken(), "/api/v1/tasks/" + b + "/move", bodyBUnderA)
                            .andReturn().getResponse().getStatus()));

            assertThat(statuses).as("round %d", round).containsExactlyInAnyOrder(200, 400);
            long roots = jdbcTemplate.queryForObject(
                    "select count(*) from tasks where project_id = ? and parent_id is null", Long.class, project);
            assertThat(roots).as("round %d: còn đúng một task gốc (không vòng lặp)", round).isOne();
        }
    }

    @Test
    void oppositeProjectMovesInParallel_exactlyOneSucceeds_noCycle() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        for (int round = 0; round < ROUNDS; round++) {
            UUID p = testData.project(user.id(), "P" + round);
            UUID q = testData.project(user.id(), "Q" + round);
            List<Integer> statuses = runInParallel(List.of(
                    () -> authPost(user.accessToken(), "/api/v1/projects/" + p + "/move",
                            "{\"parentId\": \"%s\"}".formatted(q)).andReturn().getResponse().getStatus(),
                    () -> authPost(user.accessToken(), "/api/v1/projects/" + q + "/move",
                            "{\"parentId\": \"%s\"}".formatted(p)).andReturn().getResponse().getStatus()));

            assertThat(statuses).as("round %d", round).containsExactlyInAnyOrder(200, 400);
            long roots = jdbcTemplate.queryForObject(
                    "select count(*) from projects where id in (?, ?) and parent_id is null", Long.class, p, q);
            assertThat(roots).as("round %d: còn đúng một dự án gốc (không vòng lặp)", round).isOne();
        }
    }
}
