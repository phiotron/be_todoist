package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.taskly.IntegrationTestBase;
import com.taskly.entity.LimitKind;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Đua thật (nhiều thread, Postgres thật) cho task core: khoá dự án trước khi đếm 300 (spec 8.2 #9), `id` client song
 * song (spec 12.11 — thua cuộc về khoá chính phải thành replay / id-conflict, không phải 409 chung) và hai PATCH cùng
 * version.
 */
class TaskConcurrencyApiTest extends IntegrationTestBase {

    private static final String TASKS = "/api/v1/tasks";
    private static final int THREADS = 6;

    private record Outcome(int status, String replayHeader, String code, String body) {
    }

    private List<Outcome> runInParallel(List<Callable<MvcResult>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CyclicBarrier barrier = new CyclicBarrier(calls.size());
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (Callable<MvcResult> call : calls) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    return call.call();
                }));
            }
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<MvcResult> future : futures) {
                MvcResult result = future.get();
                String json = body(result);
                String code = null;
                try {
                    code = JsonPath.read(json, "$.code");
                } catch (Exception ignored) {
                    // body không có code (thành công)
                }
                outcomes.add(new Outcome(result.getResponse().getStatus(),
                        result.getResponse().getHeader("Idempotent-Replay"), code, json));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private int rowsWithId(UUID id) {
        return jdbcTemplate.queryForObject("select count(*) from tasks where id = ?", Integer.class, id);
    }

    @Test
    void sameClientIdSameContentInParallel_oneCreated_othersReplay() throws Exception {
        for (int round = 0; round < 3; round++) { // vài vòng cho chắc có lần thua cuộc đua khoá chính
            RegisteredUser user = registerUser(uniqueEmail());
            UUID id = UUID.randomUUID();
            String body = "{\"id\": \"%s\", \"content\": \"Song song\"}".formatted(id);
            List<Callable<MvcResult>> calls = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                calls.add(() -> authPost(user.accessToken(), TASKS, body).andReturn());
            }

            List<Outcome> outcomes = runInParallel(calls);

            assertThat(outcomes).filteredOn(o -> o.status() == 201).hasSize(1);
            assertThat(outcomes).filteredOn(o -> o.status() == 200).hasSize(THREADS - 1)
                    .allSatisfy(o -> assertThat(o.replayHeader()).isEqualTo("true"));
            assertThat(rowsWithId(id)).isOne();
        }
    }

    @Test
    void sameClientIdDifferentContentInParallel_oneCreated_othersIdConflict() throws Exception {
        for (int round = 0; round < 3; round++) {
            RegisteredUser user = registerUser(uniqueEmail());
            UUID id = UUID.randomUUID();
            List<Callable<MvcResult>> calls = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                String body = "{\"id\": \"%s\", \"content\": \"Bản %d\"}".formatted(id, i);
                calls.add(() -> authPost(user.accessToken(), TASKS, body).andReturn());
            }

            List<Outcome> outcomes = runInParallel(calls);

            assertThat(outcomes).filteredOn(o -> o.status() == 201).hasSize(1);
            assertThat(outcomes).filteredOn(o -> o.status() == 409).hasSize(THREADS - 1)
                    .allSatisfy(o -> assertThat(o.code()).isEqualTo("id-conflict"));
            assertThat(rowsWithId(id)).isOne();
        }
    }

    @Test
    void sameClientIdOfTwoUsersInParallel_oneCreated_otherIdConflict() throws Exception {
        RegisteredUser alice = registerUser(uniqueEmail());
        RegisteredUser bob = registerUser(uniqueEmail());
        UUID id = UUID.randomUUID();
        String body = "{\"id\": \"%s\", \"content\": \"Trùng id\"}".formatted(id);

        List<Outcome> outcomes = runInParallel(List.of(
                () -> authPost(alice.accessToken(), TASKS, body).andReturn(),
                () -> authPost(bob.accessToken(), TASKS, body).andReturn()));

        assertThat(outcomes).extracting(Outcome::status).containsExactlyInAnyOrder(201, 409);
        assertThat(outcomes).filteredOn(o -> o.status() == 409).extracting(Outcome::code)
                .containsExactly("id-conflict");
        assertThat(rowsWithId(id)).isOne();
    }

    @Test
    void parallelCreates_neverExceedOpenTaskLimit_lockedBeforeCounting() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        usageLimits.overrideMax(LimitKind.PROJECT_ACTIVE_TASKS, 3);
        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String body = "{\"projectId\": \"%s\", \"content\": \"T%d\"}".formatted(project, i);
            calls.add(() -> authPost(user.accessToken(), TASKS, body).andReturn());
        }

        List<Outcome> outcomes = runInParallel(calls);

        assertThat(outcomes).filteredOn(o -> o.status() == 201).hasSize(3);
        assertThat(outcomes).filteredOn(o -> o.status() == 422).hasSize(5)
                .allSatisfy(o -> assertThat(o.code()).isEqualTo("limit-reached"));
        assertThat(jdbcTemplate.queryForObject("select count(*) from tasks where project_id = ?", Integer.class,
                project)).isEqualTo(3);
        // sort_order không trùng trong cùng vùng chứa
        assertThat(jdbcTemplate.queryForObject("select count(distinct sort_order) from tasks where project_id = ?",
                Integer.class, project)).isEqualTo(3);
    }

    @Test
    void twoPatchesWithTheSameVersionInParallel_oneWins_otherGets409VersionConflict() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID id = readUuid(authPost(user.accessToken(), TASKS, "{\"content\": \"Gốc\"}"), "$.id");
        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String body = "{\"version\": 0, \"content\": \"Sửa %d\"}".formatted(i);
            calls.add(() -> authPatch(user.accessToken(), TASKS + "/" + id, body).andReturn());
        }

        List<Outcome> outcomes = runInParallel(calls);

        assertThat(outcomes).extracting(Outcome::status).containsExactlyInAnyOrder(200, 409);
        assertThat(outcomes).filteredOn(o -> o.status() == 409).extracting(Outcome::code)
                .containsExactly("version-conflict");
        assertThat(testData.taskVersion(id)).isOne();
    }
}
