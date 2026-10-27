package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.IntegrationTestBase;
import com.taskly.support.LockWaits;
import com.taskly.support.TestData.TaskRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Mọi đường GHI vào task đi qua cổng khoá P rồi đọc lại (PATCH, DELETE, tasks/batch SCHEDULE / PRIORITY / DELETE,
 * overdue/reschedule). Test xác định: kết nối riêng giữ khoá P, request phải BỊ CHẶN (LockWaits), kết nối đó đổi trạng
 * thái (lưu trữ dự án / section, xoá, chuyển, tăng version) rồi commit → request phải thấy trạng thái mới.
 */
class TaskWriteGateApiTest extends IntegrationTestBase {

    @Autowired
    private DataSource dataSource;

    @FunctionalInterface
    private interface Sql {
        void run(Connection held) throws Exception;
    }

    private record Result(int status, String body) {
    }

    private Result held(UUID project, Callable<Result> request, Sql during) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Connection held = dataSource.getConnection();
        held.setAutoCommit(false);
        try (PreparedStatement ps = held.prepareStatement("select id from projects where id = ? for no key update")) {
            ps.setObject(1, project);
            ps.executeQuery().close();
        }
        try {
            Future<Result> future = pool.submit(request);
            LockWaits.awaitBlockedBy(jdbcTemplate, LockWaits.backendPid(held), 1);
            during.run(held);
            held.commit();
            held.close();
            held = null;
            return future.get(20, TimeUnit.SECONDS);
        } finally {
            if (held != null) {
                held.rollback();
                held.close();
            }
            pool.shutdownNow();
        }
    }

    private static Result result(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        var r = actions.andReturn().getResponse();
        return new Result(r.getStatus(), r.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void exec(Connection c, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    private Result patch(RegisteredUser user, UUID task, String json) {
        try {
            return result(authPatch(user.accessToken(), "/api/v1/tasks/" + task, json));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Result delete(RegisteredUser user, UUID task) {
        try {
            return result(authDelete(user.accessToken(), "/api/v1/tasks/" + task));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Result batch(RegisteredUser user, String json) {
        try {
            return result(authPost(user.accessToken(), "/api/v1/tasks/batch", json));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void archiveSectionLikeService(Connection c, UUID section) throws Exception {
        exec(c, "update sections set archived_at = now() where id = ?", section);
        exec(c, "update tasks set completed_at = now() where section_id = ? and completed_at is null", section);
    }

    // ---- PATCH ----

    @Test
    void patch_projectArchivedWhileWaiting_returns409_contentUnchanged() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p = testData.project(user.id(), "P");
        UUID t = testData.task(user.id(), p, "Gốc");

        Result r = held(p, () -> patch(user, t, "{\"version\":0,\"content\":\"Mới\"}"),
                c -> exec(c, "update projects set archived_at = now() where id = ?", p));

        assertThat(r.status()).as(r.body()).isEqualTo(409);
        assertThat(jdbcTemplate.queryForObject("select content from tasks where id = ?", String.class, t))
                .isEqualTo("Gốc");
    }

    @Test
    void patch_sectionArchivedWhileWaiting_returns409() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p = testData.project(user.id(), "P");
        UUID s = testData.section(p, "S");
        UUID t = testData.insert(TaskRow.of(user.id(), p, "Gốc").section(s));

        Result r = held(p, () -> patch(user, t, "{\"version\":0,\"priority\":3}"),
                c -> archiveSectionLikeService(c, s));

        assertThat(r.status()).as(r.body()).isEqualTo(409);
        assertThat(r.body()).contains("section-archived");
    }

    @Test
    void patch_taskDeletedWhileWaiting_returns404() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p = testData.project(user.id(), "P");
        UUID t = testData.task(user.id(), p, "Gốc");

        Result r = held(p, () -> patch(user, t, "{\"version\":0,\"content\":\"Mới\"}"),
                c -> exec(c, "delete from tasks where id = ?", t));

        assertThat(r.status()).as(r.body()).isEqualTo(404);
    }

    @Test
    void patch_versionBumpedWhileWaiting_returns409VersionConflict_withCurrent() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p = testData.project(user.id(), "P");
        UUID t = testData.task(user.id(), p, "Gốc");

        Result r = held(p, () -> patch(user, t, "{\"version\":0,\"content\":\"Của tôi\"}"),
                c -> exec(c, "update tasks set content = 'Của người khác', version = version + 1 where id = ?", t));

        assertThat(r.status()).as(r.body()).isEqualTo(409);
        assertThat(r.body()).contains("version-conflict").contains("Của người khác");
        assertThat(jdbcTemplate.queryForObject("select content from tasks where id = ?", String.class, t))
                .isEqualTo("Của người khác");
    }

    @Test
    void patch_taskMovedToArchivedProjectWhileWaiting_returns409() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p = testData.project(user.id(), "P");
        UUID other = testData.project(user.id(), "Khác");
        testData.archiveProject(other);
        UUID t = testData.task(user.id(), p, "Gốc");

        Result r = held(p, () -> patch(user, t, "{\"version\":0,\"content\":\"Mới\"}"),
                c -> exec(c, "update tasks set project_id = ? where id = ?", other, t));

        assertThat(r.status()).as(r.body()).isEqualTo(409);
        assertThat(jdbcTemplate.queryForObject("select content from tasks where id = ?", String.class, t))
                .isEqualTo("Gốc");
    }

    // ---- DELETE ----

    @Test
    void delete_projectArchivedWhileWaiting_returns409_taskStays() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p = testData.project(user.id(), "P");
        UUID t = testData.task(user.id(), p, "Gốc");

        Result r = held(p, () -> delete(user, t),
                c -> exec(c, "update projects set archived_at = now() where id = ?", p));

        assertThat(r.status()).as(r.body()).isEqualTo(409);
        assertThat(jdbcTemplate.queryForObject("select count(*) from tasks where id = ?", Integer.class, t)).isEqualTo(1);
    }

    @Test
    void delete_sectionArchivedWhileWaiting_returns409_taskStays() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p = testData.project(user.id(), "P");
        UUID s = testData.section(p, "S");
        UUID t = testData.insert(TaskRow.of(user.id(), p, "Gốc").section(s));

        Result r = held(p, () -> delete(user, t), c -> archiveSectionLikeService(c, s));

        assertThat(r.status()).as(r.body()).isEqualTo(409);
        assertThat(jdbcTemplate.queryForObject("select count(*) from tasks where id = ?", Integer.class, t)).isEqualTo(1);
    }

    @Test
    void delete_taskAlreadyDeletedWhileWaiting_returns404() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p = testData.project(user.id(), "P");
        UUID t = testData.task(user.id(), p, "Gốc");

        Result r = held(p, () -> delete(user, t), c -> exec(c, "delete from tasks where id = ?", t));

        assertThat(r.status()).as(r.body()).isEqualTo(404);
    }

    // ---- tasks/batch SCHEDULE / PRIORITY / DELETE ----

    @Test
    void batchSchedule_projectArchivedWhileWaiting_returns409_dueUnchanged() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p = testData.project(user.id(), "P");
        UUID t = testData.task(user.id(), p, "Gốc");

        Result r = held(p, () -> batch(user, "{\"taskIds\":[\"%s\"],\"action\":\"SCHEDULE\",\"date\":\"2026-12-01\"}".formatted(t)),
                c -> exec(c, "update projects set archived_at = now() where id = ?", p));

        assertThat(r.status()).as(r.body()).isEqualTo(409);
        assertThat(jdbcTemplate.queryForObject("select due_date from tasks where id = ?", LocalDate.class, t)).isNull();
    }

    @Test
    void batchPriority_projectArchivedWhileWaiting_returns409_priorityUnchanged() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p = testData.project(user.id(), "P");
        UUID t = testData.task(user.id(), p, "Gốc");

        Result r = held(p, () -> batch(user, "{\"taskIds\":[\"%s\"],\"action\":\"PRIORITY\",\"priority\":1}".formatted(t)),
                c -> exec(c, "update projects set archived_at = now() where id = ?", p));

        assertThat(r.status()).as(r.body()).isEqualTo(409);
        assertThat(jdbcTemplate.queryForObject("select priority from tasks where id = ?", Integer.class, t)).isEqualTo(4);
    }

    @Test
    void batchDelete_projectArchivedWhileWaiting_returns409_taskStays() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p = testData.project(user.id(), "P");
        UUID t = testData.task(user.id(), p, "Gốc");

        Result r = held(p, () -> batch(user, "{\"taskIds\":[\"%s\"],\"action\":\"DELETE\"}".formatted(t)),
                c -> exec(c, "update projects set archived_at = now() where id = ?", p));

        assertThat(r.status()).as(r.body()).isEqualTo(409);
        assertThat(jdbcTemplate.queryForObject("select count(*) from tasks where id = ?", Integer.class, t)).isEqualTo(1);
    }

    @Test
    void batchPriority_taskDeletedWhileWaiting_returns404() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p = testData.project(user.id(), "P");
        UUID t = testData.task(user.id(), p, "Gốc");

        Result r = held(p, () -> batch(user, "{\"taskIds\":[\"%s\"],\"action\":\"PRIORITY\",\"priority\":1}".formatted(t)),
                c -> exec(c, "delete from tasks where id = ?", t));

        assertThat(r.status()).as(r.body()).isEqualTo(404);
    }

    // ---- overdue/reschedule ----

    @Test
    void rescheduleOverdue_projectArchivedWhileWaiting_skipsItsTasks() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p = testData.project(user.id(), "P");
        clock.freezeAt(java.time.Instant.parse("2026-10-23T03:00:00Z"));
        LocalDate past = LocalDate.of(2026, 10, 1);
        UUID t = testData.insert(TaskRow.of(user.id(), p, "Quá hạn").dueDate(past));

        Result r = held(p, () -> {
            try {
                return result(authPost(user.accessToken(), "/api/v1/tasks/overdue/reschedule",
                        "{\"date\":\"2026-10-30\"}"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, c -> exec(c, "update projects set archived_at = now() where id = ?", p));

        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("select due_date from tasks where id = ?", LocalDate.class, t))
                .isEqualTo(past);
    }
}
