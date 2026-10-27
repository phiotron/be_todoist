package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.entity.LimitKind;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Test đua (spec 8.2 #9, 14.1, 18.1 C5): nhiều POST song song ở mức max − 1 → đúng MỘT thành công, còn lại 422.
 * Thiếu SELECT … FOR UPDATE hàng users thì cả nhóm cùng đếm được max − 1 rồi cùng insert (số nhắc vượt max).
 */
class ReminderLimitRaceApiTest extends IntegrationTestBase {

    private static final Instant TEN_AM_HCM = Instant.parse("2026-10-23T03:00:00Z");
    private static final int THREADS = 4;

    @Test
    void parallelCreatesAtMaxMinusOne_exactlyOneSucceeds() throws Exception {
        clock.freezeAt(TEN_AM_HCM);
        RegisteredUser user = registerUser(uniqueEmail());
        UUID taskId = readUuid(authPost(user.accessToken(), "/api/v1/tasks", """
                {"content": "Họp nhóm", "dueDatetime": "2026-10-23T12:00:00"}
                """).andExpect(status().isCreated()), "$.id");
        usageLimits.overrideMax(LimitKind.REMINDERS, 3);
        Timestamp now = Timestamp.from(clock.instant());
        for (int i = 0; i < 2; i++) { // max − 1 = 2 nhắc có sẵn
            jdbcTemplate.update("insert into reminders (id, task_id, user_id, type, at, minute_offset, created_at, "
                            + "updated_at) values (?, ?, ?, 'ABSOLUTE', ?, null, ?, ?)",
                    UUID.randomUUID(), taskId, user.id(), LocalDateTime.parse("2026-10-24T09:00:00"), now, now);
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            Callable<Integer> call = () -> {
                ready.countDown();
                go.await();
                return authPost(user.accessToken(), "/api/v1/tasks/" + taskId + "/reminders", """
                        {"type": "RELATIVE", "minuteOffset": 30}
                        """).andReturn().getResponse().getStatus();
            };
            results.add(pool.submit(call));
        }
        ready.await();
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> result : results) {
            statuses.add(result.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(statuses.stream().filter(code -> code == 201).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(code -> code == 422).count()).isEqualTo(THREADS - 1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from reminders where task_id = ?", Integer.class,
                taskId)).isEqualTo(3);
    }
}
