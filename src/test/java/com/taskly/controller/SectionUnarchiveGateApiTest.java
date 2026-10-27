package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.IntegrationTestBase;
import com.taskly.entity.LimitKind;
import com.taskly.support.LockWaits;
import java.sql.Connection;
import java.sql.PreparedStatement;
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
 * unarchive section theo quy tắc chung: khoá P → đọc lại section / dự án → kiểm lại cờ → mới đổi. Test xác định: giữ
 * khoá P bằng kết nối riêng, chờ request bị chặn (LockWaits), đổi trạng thái trên kết nối đó, commit.
 */
class SectionUnarchiveGateApiTest extends IntegrationTestBase {

    @Autowired
    private DataSource dataSource;

    @FunctionalInterface
    private interface Sql {
        void run(Connection held) throws Exception;
    }

    private record Result(int status, String body) {
    }

    private int heldProject(UUID project, String token, String uri, Sql during) throws Exception {
        return heldProjectResult(project, token, uri, during).status();
    }

    private Result heldProjectResult(UUID project, String token, String uri, Sql during) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Connection held = dataSource.getConnection();
        held.setAutoCommit(false);
        try (PreparedStatement ps = held.prepareStatement("select id from projects where id = ? for no key update")) {
            ps.setObject(1, project);
            ps.executeQuery().close();
        }
        try {
            Callable<Result> call = () -> {
                var r = authPost(token, uri).andReturn().getResponse();
                return new Result(r.getStatus(), r.getContentAsString());
            };
            Future<Result> future = pool.submit(call);
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

    private void exec(Connection c, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    @Test
    void unarchive_sectionDeletedWhileWaiting_returns404() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p = testData.project(user.id(), "P");
        UUID s = testData.section(p, "S");
        testData.archiveSection(s);

        int status = heldProject(p, user.accessToken(), "/api/v1/projects/" + p + "/sections/" + s + "/unarchive",
                held -> exec(held, "delete from sections where id = ?", s));

        assertThat(status).isEqualTo(404);
    }

    @Test
    void unarchive_projectArchivedWhileWaiting_returns409_sectionStaysArchived() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p = testData.project(user.id(), "P");
        UUID s = testData.section(p, "S");
        testData.archiveSection(s);

        int status = heldProject(p, user.accessToken(), "/api/v1/projects/" + p + "/sections/" + s + "/unarchive",
                held -> exec(held, "update projects set archived_at = now() where id = ?", p));

        assertThat(status).isEqualTo(409);
        assertThat(jdbcTemplate.queryForObject("select archived_at is not null from sections where id = ?",
                Boolean.class, s)).isTrue();
    }

    @Test
    void unarchive_sectionAlreadyUnarchivedWhileWaiting_returns200_withoutRecountingItself() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p = testData.project(user.id(), "P");
        UUID s = testData.section(p, "S");
        testData.archiveSection(s);
        usageLimits.overrideMax(LimitKind.PROJECT_SECTIONS, 1);

        int status = heldProject(p, user.accessToken(), "/api/v1/projects/" + p + "/sections/" + s + "/unarchive",
                held -> exec(held, "update sections set archived_at = null where id = ?", s));

        assertThat(status).isEqualTo(200);
    }
}
