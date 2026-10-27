package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.IntegrationTestBase;
import com.taskly.support.LockWaits;
import com.taskly.support.TestData.TaskRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Batch MOVE kiểm dự án đích "chưa lưu trữ" TRƯỚC khi chờ khoá. Trong lúc chờ, archive(đích) commit; sau khi nhận khoá
 * phải đọc lại đích (422), task KHÔNG được chuyển vào dự án đã lưu trữ.
 */
class BatchMoveStaleTargetApiTest extends IntegrationTestBase {

    @Autowired
    private DataSource dataSource;

    @Test
    void batchMoveWaitingOnTargetLock_targetArchivedMeanwhile_returns422_taskNotMoved() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID source = testData.project(user.id(), "Nguồn");
        UUID target = testData.project(user.id(), "Đích");
        UUID task = testData.insert(TaskRow.of(user.id(), source, "T"));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Connection held = dataSource.getConnection();
        try {
            held.setAutoCommit(false);
            try (PreparedStatement ps = held.prepareStatement("select id from projects where id = ? for no key update")) {
                ps.setObject(1, target);
                ps.executeQuery().close();
            }
            Future<Integer> batch = pool.submit(() -> authPost(user.accessToken(), "/api/v1/tasks/batch",
                    "{\"action\": \"MOVE\", \"projectId\": \"%s\", \"taskIds\": [\"%s\"]}".formatted(target, task))
                    .andReturn().getResponse().getStatus());
            LockWaits.awaitBlockedBy(jdbcTemplate, LockWaits.backendPid(held), 1);
            try (PreparedStatement ps = held.prepareStatement("update projects set archived_at = ? where id = ?")) {
                ps.setTimestamp(1, Timestamp.from(Instant.now()));
                ps.setObject(2, target);
                ps.executeUpdate(); // "archive(đích)" thắng
            }
            held.commit();
            held.close();
            held = null;

            assertThat(batch.get(20, TimeUnit.SECONDS)).isEqualTo(422);
            assertThat(jdbcTemplate.queryForObject("select project_id from tasks where id = ?", UUID.class, task))
                    .as("task không bị chuyển vào dự án lưu trữ").isEqualTo(source);
        } finally {
            if (held != null) {
                held.rollback();
                held.close();
            }
            pool.shutdownNow();
        }
    }
}
