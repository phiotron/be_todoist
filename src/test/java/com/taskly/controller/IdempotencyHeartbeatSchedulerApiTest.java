package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.taskly.IntegrationTestBase;
import com.taskly.support.LockWaits;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * Heartbeat THẬT (@Scheduled, không gọi renewLeases() bằng tay): bật job nền, rút TTL "đang xử lý" còn 1 giây và chu kỳ
 * gia hạn còn 200 ms. Thao tác giả lập = tạo task bị chặn bởi khoá dự án do kết nối riêng giữ, chạy lâu hơn 2 lần TTL.
 * Request trùng giữa chừng phải nhận 409 idempotency-in-progress (hàng khoá không hết hạn) và thao tác chạy đúng MỘT lần.
 * Context riêng (thuộc tính khác) nên có Postgres thứ hai; thời gian là giờ thật (MutableClock không bị dời).
 */
@TestPropertySource(properties = {
        "app.jobs.enabled=true",
        "taskly.idempotency.in-progress-ttl=PT1S",
        "taskly.idempotency.renew-interval=PT0.2S"})
class IdempotencyHeartbeatSchedulerApiTest extends IntegrationTestBase {

    @Autowired
    private DataSource dataSource;

    private record Response(int status, String body, String replayHeader) {
    }

    private Response createTask(String token, UUID project, String key) throws Exception {
        var response = mockMvc.perform(post("/api/v1/tasks")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\": \"%s\", \"content\": \"Chạy lâu\"}".formatted(project)))
                .andReturn().getResponse();
        return new Response(response.getStatus(), response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8),
                response.getHeader("Idempotent-Replay"));
    }

    @Test
    void operationRunningLongerThanTtl_isRenewedByRealScheduler_duplicateGets409_runsOnce() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        String key = "heartbeat-" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Connection held = dataSource.getConnection();
        held.setAutoCommit(false);
        try (PreparedStatement ps = held.prepareStatement("select id from projects where id = ? for no key update")) {
            ps.setObject(1, project);
            ps.executeQuery().close();
        }
        try {
            Future<Response> first = pool.submit(() -> createTask(user.accessToken(), project, key));
            LockWaits.awaitBlockedBy(jdbcTemplate, LockWaits.backendPid(held), 1);
            Thread.sleep(2500); // > 2 × TTL (1 s): không có heartbeat thì hàng khoá đã hết hạn từ lâu

            // bản trùng chạy ở luồng riêng + hạn chờ: nếu hàng khoá đã hết hạn nó chiếm lại rồi KẸT ở khoá P (chạy lần hai)
            Response duplicate = pool.submit(() -> createTask(user.accessToken(), project, key)).get(8, TimeUnit.SECONDS);
            assertThat(duplicate.status()).isEqualTo(409);
            assertThat(duplicate.body()).contains("idempotency-in-progress");

            held.rollback();
            held.close();
            held = null;
            Response result = first.get(20, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(201);
            assertThat(jdbcTemplate.queryForObject("select count(*) from tasks where project_id = ?", Integer.class,
                    project)).as("thao tác chỉ chạy một lần").isEqualTo(1);

            Response replay = createTask(user.accessToken(), project, key);
            assertThat(replay.status()).isEqualTo(201);
            assertThat(replay.replayHeader()).isEqualTo("true");
            assertThat(jdbcTemplate.queryForObject("select count(*) from tasks where project_id = ?", Integer.class,
                    project)).isEqualTo(1);
        } finally {
            if (held != null) {
                held.rollback();
                held.close();
            }
            pool.shutdownNow();
        }
    }
}
