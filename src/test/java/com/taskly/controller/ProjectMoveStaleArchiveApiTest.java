package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.IntegrationTestBase;
import com.taskly.support.LockWaits;
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
 * Move dự án nạp + kiểm "chưa lưu trữ" TRƯỚC khi chờ khoá workspace. Trong lúc chờ, archive(cha) commit và lưu trữ cả
 * dự án; move chạy tiếp phải đọc lại hàng (409), KHÔNG được ghi archived_at = null (dự án "sống lại" dưới cha lưu trữ).
 */
class ProjectMoveStaleArchiveApiTest extends IntegrationTestBase {

    @Autowired
    private DataSource dataSource;

    /**
     * Khoảng chờ khoá HÀNG (không phải khoá workspace): archive(cha) đang chờ khoá hàng con, move(con) vào sau. Move phải
     * xếp sau archive (archive giữ khoá workspace) → thấy dự án đã lưu trữ → 409; không chạy tiếp với bản đọc cũ.
     */
    @Test
    void moveQueuedBehindArchiveWaitingOnRowLock_returns409_notMovesArchivedProject() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID parent = testData.project(user.id(), "Cha");
        UUID project = testData.childProject(user.id(), parent, "Con");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Connection held = dataSource.getConnection();
        try {
            held.setAutoCommit(false);
            try (PreparedStatement ps = held.prepareStatement("select id from projects where id = ? for no key update")) {
                ps.setObject(1, project);
                ps.executeQuery().close();
            }
            int pid = LockWaits.backendPid(held);
            Future<Integer> archive = pool.submit(() -> authPost(user.accessToken(), "/api/v1/projects/" + parent
                    + "/archive").andReturn().getResponse().getStatus());
            LockWaits.awaitBlockedBy(jdbcTemplate, pid, 1);
            Future<Integer> move = pool.submit(() -> authPost(user.accessToken(), "/api/v1/projects/" + project
                    + "/move", "{\"parentId\": null}").andReturn().getResponse().getStatus());
            LockWaits.awaitBlockedBy(jdbcTemplate, pid, 2);
            held.rollback();
            held.close();
            held = null;

            assertThat(archive.get(20, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(move.get(20, TimeUnit.SECONDS)).isEqualTo(409);
            assertThat(jdbcTemplate.queryForObject("select parent_id from projects where id = ?", UUID.class, project))
                    .as("vẫn nằm trong cụm lưu trữ của cha").isEqualTo(parent);
        } finally {
            if (held != null) {
                held.rollback();
                held.close();
            }
            pool.shutdownNow();
        }
    }

    @Test
    void moveWaitingOnWorkspaceLock_doesNotResurrectProjectArchivedMeanwhile() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID parent = testData.project(user.id(), "Cha");
        UUID project = testData.childProject(user.id(), parent, "Con");
        UUID workspace = testData.workspaceOf(user.id());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Connection held = dataSource.getConnection();
        try {
            held.setAutoCommit(false);
            try (PreparedStatement ps = held.prepareStatement("select id from workspaces where id = ? for no key update")) {
                ps.setObject(1, workspace);
                ps.executeQuery().close();
            }
            Future<Integer> move = pool.submit(() -> authPost(user.accessToken(), "/api/v1/projects/" + project + "/move",
                    "{\"parentId\": null}").andReturn().getResponse().getStatus());
            LockWaits.awaitBlockedBy(jdbcTemplate, LockWaits.backendPid(held), 1);
            // "archive(cha)" thắng và commit: lưu trữ cả cha lẫn con
            jdbcTemplate.update("update projects set archived_at = ? where id in (?, ?)",
                    Timestamp.from(Instant.now()), parent, project);
            held.rollback();
            held.close();
            held = null;

            assertThat(move.get(20, TimeUnit.SECONDS)).isEqualTo(409);
            assertThat(jdbcTemplate.queryForObject("select archived_at from projects where id = ?", Timestamp.class,
                    project)).as("dự án vẫn lưu trữ").isNotNull();
            assertThat(jdbcTemplate.queryForObject("select parent_id from projects where id = ?", UUID.class, project))
                    .isEqualTo(parent);
        } finally {
            if (held != null) {
                held.rollback();
                held.close();
            }
            pool.shutdownNow();
        }
    }
}
