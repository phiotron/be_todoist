package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.IntegrationTestBase;
import com.taskly.support.LockWaits;
import com.taskly.support.TestData.TaskRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
 * Cổng KHOÁ DỰ ÁN (P) cho mọi thao tác ghi dưới một dự án: create task / section, reopen / undo (cả batch), duplicate,
 * sửa / xoá section, reorder section / task. Mỗi request phải (1) CHỜ khi một transaction khác đang giữ P, rồi (2) ĐỌC LẠI
 * trạng thái đã commit trong lúc chờ (cờ lưu trữ dự án / section, trạng thái + dự án của cha) trước khi ghi. Test xác
 * định: giữ P bằng kết nối riêng, chờ request bị chặn (LockWaits), đổi trạng thái trên chính kết nối đó, commit.
 */
class ProjectLockGateApiTest extends IntegrationTestBase {

    @Autowired
    private DataSource dataSource;

    private record Result(int status, String body) {
    }

    private Connection hold(String table, UUID id) throws Exception {
        Connection c = dataSource.getConnection();
        c.setAutoCommit(false);
        try (PreparedStatement ps = c.prepareStatement("select id from " + table + " where id = ? for no key update")) {
            ps.setObject(1, id);
            ps.executeQuery().close();
        }
        return c;
    }

    private void exec(Connection c, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    @FunctionalInterface
    private interface Sql {
        void run(Connection held) throws Exception;
    }

    private Result call(Callable<org.springframework.test.web.servlet.ResultActions> request) throws Exception {
        var response = request.call().andReturn().getResponse();
        return new Result(response.getStatus(), response.getContentAsString());
    }

    /** Giữ khoá P, chạy request, chờ nó bị chặn, chạy `during` trên kết nối đang giữ, commit, trả kết quả request. */
    private Result heldProject(UUID project, Callable<org.springframework.test.web.servlet.ResultActions> request,
                               Sql during) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Connection held = hold("projects", project);
        try {
            Future<Result> future = pool.submit(() -> call(request));
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

    private String createBody(UUID project, UUID section, UUID parent, String content) {
        StringBuilder sb = new StringBuilder("{\"content\": \"" + content + "\"");
        if (project != null) {
            sb.append(", \"projectId\": \"").append(project).append('"');
        }
        if (section != null) {
            sb.append(", \"sectionId\": \"").append(section).append('"');
        }
        if (parent != null) {
            sb.append(", \"parentId\": \"").append(parent).append('"');
        }
        return sb.append('}').toString();
    }

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    // ---- A. tạo task: đọc lại dự án + cha sau khoá ----

    @Test
    void createSubtask_parentMovedToAnotherProjectWhileWaiting_isRejected_notInsertedWithStaleProject() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p1 = testData.project(user.id(), "P1");
        UUID p2 = testData.project(user.id(), "P2");
        UUID parent = testData.task(user.id(), p1, "Cha");

        Result result = heldProject(p1, () -> authPost(user.accessToken(), "/api/v1/tasks",
                createBody(null, null, parent, "Con")), held -> exec(held,
                "update tasks set project_id = ? where id = ?", p2, parent));

        assertThat(result.status()).isEqualTo(409);
        assertThat(count("select count(*) from tasks where parent_id = ?", parent)).isZero();
    }

    @Test
    void createSubtask_parentClosedWhileWaiting_returnsParentCompleted() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p1 = testData.project(user.id(), "P1");
        UUID parent = testData.task(user.id(), p1, "Cha");

        Result result = heldProject(p1, () -> authPost(user.accessToken(), "/api/v1/tasks",
                createBody(null, null, parent, "Con")), held -> exec(held,
                "update tasks set completed_at = now() where id = ?", parent));

        assertThat(result.status()).isEqualTo(409);
        assertThat(result.body()).contains("parent-completed");
        assertThat(count("select count(*) from tasks where parent_id = ?", parent)).isZero();
    }

    @Test
    void createSubtask_projectArchivedWhileWaiting_returnsProjectArchived() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p1 = testData.project(user.id(), "P1");
        UUID parent = testData.task(user.id(), p1, "Cha");

        Result result = heldProject(p1, () -> authPost(user.accessToken(), "/api/v1/tasks",
                createBody(null, null, parent, "Con")), held -> exec(held,
                "update projects set archived_at = now() where id = ?", p1));

        assertThat(result.status()).isEqualTo(409);
        assertThat(result.body()).contains("project-archived");
        assertThat(count("select count(*) from tasks where parent_id = ?", parent)).isZero();
    }

    @Test
    void createTask_projectArchivedWhileWaiting_returnsTargetArchived() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p1 = testData.project(user.id(), "P1");

        Result result = heldProject(p1, () -> authPost(user.accessToken(), "/api/v1/tasks",
                createBody(p1, null, null, "Mới")), held -> exec(held,
                "update projects set archived_at = now() where id = ?", p1));

        assertThat(result.status()).isEqualTo(422);
        assertThat(result.body()).contains("target-archived");
        assertThat(count("select count(*) from tasks where project_id = ?", p1)).isZero();
    }

    // ---- B. reopen / undo / batch REOPEN / duplicate: kiểm lại cờ lưu trữ sau khoá ----

    private UUID completedTaskIn(UUID user, UUID project, UUID section) {
        return testData.insert(TaskRow.of(user, project, "Xong").section(section)
                .completedAt(java.time.Instant.now().minusSeconds(60)));
    }

    @Test
    void reopen_sectionArchivedWhileWaiting_returnsSectionArchived() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p = testData.project(user.id(), "P");
        UUID s = testData.section(p, "S");
        UUID task = completedTaskIn(user.id(), p, s);

        Result result = heldProject(p, () -> authPost(user.accessToken(), "/api/v1/tasks/" + task + "/reopen"),
                held -> exec(held, "update sections set archived_at = now() where id = ?", s));

        assertThat(result.status()).isEqualTo(409);
        assertThat(result.body()).contains("section-archived");
        assertThat(count("select count(*) from tasks where id = ? and completed_at is null", task)).isZero();
    }

    @Test
    void reopen_projectArchivedWhileWaiting_returnsProjectArchived() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p = testData.project(user.id(), "P");
        UUID task = completedTaskIn(user.id(), p, null);

        Result result = heldProject(p, () -> authPost(user.accessToken(), "/api/v1/tasks/" + task + "/reopen"),
                held -> exec(held, "update projects set archived_at = now() where id = ?", p));

        assertThat(result.status()).isEqualTo(409);
        assertThat(result.body()).contains("project-archived");
        assertThat(count("select count(*) from tasks where id = ? and completed_at is null", task)).isZero();
    }

    @Test
    void batchReopen_sectionArchivedWhileWaiting_returnsSectionArchived() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p = testData.project(user.id(), "P");
        UUID s = testData.section(p, "S");
        UUID task = completedTaskIn(user.id(), p, s);

        Result result = heldProject(p, () -> authPost(user.accessToken(), "/api/v1/tasks/batch",
                "{\"action\": \"REOPEN\", \"taskIds\": [\"%s\"]}".formatted(task)),
                held -> exec(held, "update sections set archived_at = now() where id = ?", s));

        assertThat(result.status()).isEqualTo(409);
        assertThat(count("select count(*) from tasks where id = ? and completed_at is null", task)).isZero();
    }

    @Test
    void duplicate_sectionArchivedWhileWaiting_returnsSectionArchived() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p = testData.project(user.id(), "P");
        UUID s = testData.section(p, "S");
        UUID task = testData.insert(TaskRow.of(user.id(), p, "Gốc").section(s));

        Result result = heldProject(p, () -> authPost(user.accessToken(), "/api/v1/tasks/" + task + "/duplicate"),
                held -> exec(held, "update sections set archived_at = now() where id = ?", s));

        assertThat(result.status()).isEqualTo(409);
        assertThat(result.body()).contains("section-archived");
        assertThat(count("select count(*) from tasks where project_id = ?", p)).isEqualTo(1);
    }

    @Test
    void duplicate_projectArchivedWhileWaiting_returnsProjectArchived() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p = testData.project(user.id(), "P");
        UUID task = testData.task(user.id(), p, "Gốc");

        Result result = heldProject(p, () -> authPost(user.accessToken(), "/api/v1/tasks/" + task + "/duplicate"),
                held -> exec(held, "update projects set archived_at = now() where id = ?", p));

        assertThat(result.status()).isEqualTo(409);
        assertThat(result.body()).contains("project-archived");
        assertThat(count("select count(*) from tasks where project_id = ?", p)).isEqualTo(1);
    }

    // ---- C. section: create kiểm lại; update / delete khoá P ----

    @Test
    void createSection_projectArchivedWhileWaiting_returnsProjectArchived() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p = testData.project(user.id(), "P");

        Result result = heldProject(p, () -> authPost(user.accessToken(), "/api/v1/projects/" + p + "/sections",
                "{\"name\": \"S\"}"), held -> exec(held, "update projects set archived_at = now() where id = ?", p));

        assertThat(result.status()).isEqualTo(409);
        assertThat(result.body()).contains("project-archived");
        assertThat(count("select count(*) from sections where project_id = ?", p)).isZero();
    }

    @Test
    void deleteSection_waitsForProjectLock() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p = testData.project(user.id(), "P");
        UUID s = testData.section(p, "S");

        Result result = heldProject(p, () -> authDelete(user.accessToken(), "/api/v1/projects/" + p + "/sections/" + s),
                held -> {
                });

        assertThat(result.status()).isEqualTo(204);
        assertThat(count("select count(*) from sections where id = ?", s)).isZero();
    }

    @Test
    void updateSection_waitsForProjectLock_andRechecksProjectArchived() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p = testData.project(user.id(), "P");
        UUID s = testData.section(p, "S");

        Result result = heldProject(p, () -> authPatch(user.accessToken(), "/api/v1/projects/" + p + "/sections/" + s,
                "{\"name\": \"Mới\"}"), held -> exec(held, "update projects set archived_at = now() where id = ?", p));

        assertThat(result.status()).isEqualTo(409);
        assertThat(jdbcTemplate.queryForObject("select name from sections where id = ?", String.class, s)).isEqualTo("S");
    }

    @Test
    void taskCreateThenSectionDelete_serializeByProjectLock_noRawIntegrityError() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p = testData.project(user.id(), "P");
        UUID s = testData.section(p, "S");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Connection held = hold("projects", p);
        try {
            int holderPid = LockWaits.backendPid(held);
            Future<Result> create = pool.submit(() -> call(() -> authPost(user.accessToken(), "/api/v1/tasks",
                    createBody(p, s, null, "Đua"))));
            LockWaits.awaitBlockedBy(jdbcTemplate, holderPid, 1); // create đã qua kiểm tra, chờ P
            Future<Result> delete = pool.submit(() -> call(() -> authDelete(user.accessToken(),
                    "/api/v1/projects/" + p + "/sections/" + s)));
            LockWaits.awaitBlockedBy(jdbcTemplate, holderPid, 2); // xoá section cũng phải chờ P (xếp hàng sau create)
            held.rollback();
            held.close();
            held = null;

            assertThat(create.get(20, TimeUnit.SECONDS).status()).isEqualTo(201);
            assertThat(delete.get(20, TimeUnit.SECONDS).status()).isEqualTo(204);
            assertThat(count("select count(*) from tasks where section_id = ?", s)).isZero(); // cascade
        } finally {
            if (held != null) {
                held.rollback();
                held.close();
            }
            pool.shutdownNow();
        }
    }

    // ---- D. reorder section / task: cổng khoá P + đọc lại + khoá hàng tăng dần khi đánh số lại ----

    private String reorderBody(String type, UUID id, UUID afterId) {
        return "{\"type\": \"%s\", \"id\": \"%s\", \"afterId\": \"%s\"}".formatted(type, id, afterId);
    }

    @Test
    void reorderSection_waitsForProjectLock_andRechecksArchivedSection() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p = testData.project(user.id(), "P");
        UUID a = testData.section(p, "A");
        UUID b = testData.section(p, "B");

        Result result = heldProject(p, () -> authPost(user.accessToken(), "/api/v1/reorder",
                reorderBody("SECTION", b, a)), held -> exec(held,
                "update sections set archived_at = now() where id = ?", b));

        assertThat(result.status()).isEqualTo(409);
        assertThat(result.body()).contains("section-archived");
    }

    @Test
    void reorderSection_waitsForProjectLock_thenSucceeds() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p = testData.project(user.id(), "P");
        UUID a = testData.section(p, "A");
        UUID b = testData.section(p, "B");

        Result result = heldProject(p, () -> authPost(user.accessToken(), "/api/v1/reorder",
                reorderBody("SECTION", b, a)), held -> {
                });

        assertThat(result.status()).isEqualTo(200);
    }

    @Test
    void reorderTask_waitsForProjectLock_andRechecksArchivedProject() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p = testData.project(user.id(), "P");
        UUID a = testData.task(user.id(), p, "A");
        UUID b = testData.task(user.id(), p, "B");

        Result result = heldProject(p, () -> authPost(user.accessToken(), "/api/v1/reorder",
                reorderBody("TASK", b, a)), held -> exec(held, "update projects set archived_at = now() where id = ?", p));

        assertThat(result.status()).isEqualTo(409);
        assertThat(result.body()).contains("project-archived");
    }

    @Test
    void reorderTask_waitsForProjectLock_andRechecksArchivedSection() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p = testData.project(user.id(), "P");
        UUID s = testData.section(p, "S");
        UUID a = testData.insert(TaskRow.of(user.id(), p, "A").section(s));
        UUID b = testData.insert(TaskRow.of(user.id(), p, "B").section(s));

        Result result = heldProject(p, () -> authPost(user.accessToken(), "/api/v1/reorder",
                reorderBody("TASK", b, a)), held -> exec(held, "update sections set archived_at = now() where id = ?", s));

        assertThat(result.status()).isEqualTo(409);
        assertThat(result.body()).contains("section-archived");
    }

    private boolean taskLockedByOthers(UUID id) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("select id from tasks where id = ? for no key update skip locked")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return !rs.next();
            }
        }
    }

    @Test
    void reorderTask_renumbering_locksTaskRowsAscendingBeforeUpdating() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p = testData.project(user.id(), "P");
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ids.add(testData.task(user.id(), p, "T" + i));
        }
        UUID min = ids.stream().min(Comparator.naturalOrder()).orElseThrow();
        List<UUID> others = ids.stream().filter(id -> !id.equals(min)).toList();
        UUID moved = others.get(0);
        // anh em theo thứ tự: others[1], others[2], min (id nhỏ nhất đứng CUỐI); khoảng trống 1 → phải đánh số lại
        List<UUID> siblings = List.of(others.get(1), others.get(2), min);
        for (int i = 0; i < siblings.size(); i++) {
            jdbcTemplate.update("update tasks set sort_order = ? where id = ?", i + 1, siblings.get(i));
        }
        jdbcTemplate.update("update tasks set sort_order = 100000 where id = ?", moved);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Connection held = hold("tasks", min);
        try {
            Future<Result> reorder = pool.submit(() -> call(() -> authPost(user.accessToken(), "/api/v1/reorder",
                    reorderBody("TASK", moved, siblings.get(0)))));
            LockWaits.awaitBlockedBy(jdbcTemplate, LockWaits.backendPid(held), 1);
            for (UUID id : others) {
                assertThat(taskLockedByOthers(id)).as("request đã giữ %s khi còn chờ khoá id nhỏ nhất", id).isFalse();
            }
            held.rollback();
            held.close();
            held = null;
            assertThat(reorder.get(20, TimeUnit.SECONDS).status()).isEqualTo(200);
        } finally {
            if (held != null) {
                held.rollback();
                held.close();
            }
            pool.shutdownNow();
        }
    }
}
