package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.taskly.IntegrationTestBase;
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

/** `id` client song song cho project / section (spec 12.11): bên thua đua phải ra replay 200 / 409 id-conflict, không phải 409 chung. */
class ClientIdRaceApiTest extends IntegrationTestBase {

    private static final int THREADS = 6;

    private record Outcome(int status, String replayHeader, String code) {
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
                String code = null;
                try {
                    code = JsonPath.read(body(result), "$.code");
                } catch (Exception ignored) {
                    // thành công: không có code
                }
                outcomes.add(new Outcome(result.getResponse().getStatus(),
                        result.getResponse().getHeader("Idempotent-Replay"), code));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sameProjectIdSameContentInParallel_oneCreated_othersReplay() throws Exception {
        for (int round = 0; round < 3; round++) {
            RegisteredUser user = registerUser(uniqueEmail());
            UUID id = UUID.randomUUID();
            String json = "{\"id\": \"%s\", \"name\": \"Song song\"}".formatted(id);
            List<Callable<MvcResult>> calls = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                calls.add(() -> authPost(user.accessToken(), "/api/v1/projects", json).andReturn());
            }

            List<Outcome> outcomes = runInParallel(calls);

            assertThat(outcomes).filteredOn(o -> o.status() == 201).hasSize(1);
            assertThat(outcomes).filteredOn(o -> o.status() == 200).hasSize(THREADS - 1)
                    .allSatisfy(o -> assertThat(o.replayHeader()).isEqualTo("true"));
            assertThat(jdbcTemplate.queryForObject("select count(*) from projects where id = ?", Integer.class, id))
                    .isOne();
        }
    }

    @Test
    void sameProjectIdDifferentContentInParallel_oneCreated_othersIdConflict() throws Exception {
        for (int round = 0; round < 3; round++) {
            RegisteredUser user = registerUser(uniqueEmail());
            UUID id = UUID.randomUUID();
            List<Callable<MvcResult>> calls = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                String json = "{\"id\": \"%s\", \"name\": \"Bản %d\"}".formatted(id, i);
                calls.add(() -> authPost(user.accessToken(), "/api/v1/projects", json).andReturn());
            }

            List<Outcome> outcomes = runInParallel(calls);

            assertThat(outcomes).filteredOn(o -> o.status() == 201).hasSize(1);
            assertThat(outcomes).filteredOn(o -> o.status() == 409).hasSize(THREADS - 1)
                    .allSatisfy(o -> assertThat(o.code()).isEqualTo("id-conflict"));
        }
    }

    @Test
    void sameSectionIdSameContentInParallel_oneCreated_othersReplay() throws Exception {
        for (int round = 0; round < 3; round++) {
            RegisteredUser user = registerUser(uniqueEmail());
            UUID project = testData.project(user.id(), "P");
            UUID id = UUID.randomUUID();
            String json = "{\"id\": \"%s\", \"name\": \"Song song\"}".formatted(id);
            List<Callable<MvcResult>> calls = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                calls.add(() -> authPost(user.accessToken(), "/api/v1/projects/" + project + "/sections", json)
                        .andReturn());
            }

            List<Outcome> outcomes = runInParallel(calls);

            assertThat(outcomes).filteredOn(o -> o.status() == 201).hasSize(1);
            assertThat(outcomes).filteredOn(o -> o.status() == 200).hasSize(THREADS - 1)
                    .allSatisfy(o -> assertThat(o.replayHeader()).isEqualTo("true"));
            assertThat(jdbcTemplate.queryForObject("select count(*) from sections where id = ?", Integer.class, id))
                    .isOne();
        }
    }

    @Test
    void sameSectionIdDifferentContentInParallel_oneCreated_othersIdConflict() throws Exception {
        for (int round = 0; round < 3; round++) {
            RegisteredUser user = registerUser(uniqueEmail());
            UUID project = testData.project(user.id(), "P");
            UUID id = UUID.randomUUID();
            List<Callable<MvcResult>> calls = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                String json = "{\"id\": \"%s\", \"name\": \"Bản %d\"}".formatted(id, i);
                calls.add(() -> authPost(user.accessToken(), "/api/v1/projects/" + project + "/sections", json)
                        .andReturn());
            }

            List<Outcome> outcomes = runInParallel(calls);

            assertThat(outcomes).filteredOn(o -> o.status() == 201).hasSize(1);
            assertThat(outcomes).filteredOn(o -> o.status() == 409).hasSize(THREADS - 1)
                    .allSatisfy(o -> assertThat(o.code()).isEqualTo("id-conflict"));
        }
    }
}
