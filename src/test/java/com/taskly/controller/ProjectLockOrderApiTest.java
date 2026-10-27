package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.IntegrationTestBase;
import com.taskly.support.LockWaits;
import com.taskly.support.TestData.TaskRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Thứ tự khoá dự án (spec 8.2 #9) phải đúng trên CẢ transaction, không chỉ trong một lần gọi lockProjects: giữ khoá dự
 * án có id NHỎ NHẤT bằng một kết nối riêng, bắn request, chờ nó bị chặn, rồi kiểm các dự án id LỚN HƠN chưa bị request
 * giữ (nếu nó đã giữ id lớn rồi mới chờ id nhỏ thì request khác khoá ngược thứ tự sẽ deadlock — 40P01 → 409 giả).
 */
class ProjectLockOrderApiTest extends IntegrationTestBase {

    @Autowired
    private DataSource dataSource;

    /** Kết nối riêng: true nếu hàng đang bị transaction KHÁC giữ (SKIP LOCKED không trả hàng). */
    private boolean lockedByOthers(UUID id) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("select id from projects where id = ? for no key update skip locked")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return !rs.next();
            }
        }
    }

    private Connection holdLock(UUID id) throws Exception {
        Connection c = dataSource.getConnection();
        c.setAutoCommit(false);
        try (PreparedStatement ps = c.prepareStatement("select id from projects where id = ? for no key update")) {
            ps.setObject(1, id);
            ps.executeQuery().close();
        }
        return c;
    }

    private void release(Connection c) throws Exception {
        c.rollback();
        c.close();
    }

    /** n dự án gốc, sắp theo id TĂNG DẦN. */
    private List<UUID> orderedProjects(UUID userId, int n) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(testData.project(userId, "P" + i));
        }
        ids.sort(Comparator.naturalOrder());
        return ids;
    }

    /** Dự án cha + dự án con có id NHỎ HƠN cha. */
    private UUID[] parentAndSmallerChild(UUID userId) {
        for (int i = 0; i < 200; i++) {
            UUID parent = testData.project(userId, "Cha" + i);
            UUID child = testData.childProject(userId, parent, "Con" + i);
            if (child.compareTo(parent) < 0) {
                return new UUID[] {parent, child};
            }
        }
        throw new AssertionError("không sinh được con < cha");
    }

    /** Giữ khoá `held`, chạy request, chờ nó bị chặn, kiểm các id `mustBeFree`, nhả khoá → trả status. */
    private int runBlockedBy(UUID held, Callable3 request, UUID... mustBeFree) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Connection lock = holdLock(held);
        try {
            Future<Integer> future = pool.submit(request::status);
            LockWaits.awaitBlockedBy(jdbcTemplate, LockWaits.backendPid(lock), 1);
            for (UUID id : mustBeFree) {
                assertThat(lockedByOthers(id)).as("request đã giữ %s khi còn chờ khoá id nhỏ hơn", id).isFalse();
            }
            release(lock);
            lock = null;
            return future.get(20, TimeUnit.SECONDS);
        } finally {
            if (lock != null) {
                release(lock);
            }
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface Callable3 {
        int status() throws Exception;
    }

    @Test
    void archive_locksProjectsAscendingBeforeUpdating() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID[] pair = parentAndSmallerChild(user.id());
        int status = runBlockedBy(pair[1], () -> authPost(user.accessToken(), "/api/v1/projects/" + pair[0] + "/archive")
                .andReturn().getResponse().getStatus(), pair[0]);
        assertThat(status).isEqualTo(200);
    }

    @Test
    void unarchive_locksProjectsAscendingBeforeUpdating() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID[] pair = parentAndSmallerChild(user.id());
        jdbcTemplate.update("update projects set archived_at = ? where id in (?, ?)",
                Timestamp.from(Instant.now().minusSeconds(60)), pair[0], pair[1]);
        int status = runBlockedBy(pair[1], () -> authPost(user.accessToken(), "/api/v1/projects/" + pair[0] + "/unarchive")
                .andReturn().getResponse().getStatus(), pair[0]);
        assertThat(status).isEqualTo(200);
    }

    @Test
    void delete_locksProjectsAscendingBeforeCascading() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID[] pair = parentAndSmallerChild(user.id());
        int status = runBlockedBy(pair[1], () -> authDelete(user.accessToken(), "/api/v1/projects/" + pair[0])
                .andReturn().getResponse().getStatus(), pair[0]);
        assertThat(status).isIn(200, 204);
    }

    @Test
    void create_renumberingSiblings_locksThemAscendingBeforeUpdating() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID parent = testData.project(user.id(), "Cha");
        UUID s1 = testData.childProject(user.id(), parent, "S1");
        UUID s2 = testData.childProject(user.id(), parent, "S2");
        UUID small = s1.compareTo(s2) < 0 ? s1 : s2;
        UUID large = small.equals(s1) ? s2 : s1;
        // id LỚN có sort_order nhỏ (1), id NHỎ có sort_order 2: đánh số lại theo (sortOrder, id) sẽ chạm `large` trước
        jdbcTemplate.update("update projects set sort_order = 1 where id = ?", large);
        jdbcTemplate.update("update projects set sort_order = 2 where id = ?", small);
        int status = runBlockedBy(small, () -> authPost(user.accessToken(), "/api/v1/projects",
                "{\"name\": \"Moi\", \"parentId\": \"%s\", \"afterId\": null}".formatted(parent))
                .andReturn().getResponse().getStatus(), large);
        assertThat(status).isEqualTo(201);
    }

    /** Request KHÔNG đánh số lại thì không được chờ khoá hàng anh em: giữ khoá `sibling`, request phải xong ngay. */
    private int statusWhileHolding(UUID sibling, Callable3 request) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Connection lock = holdLock(sibling);
        try {
            return pool.submit(request::status).get(10, TimeUnit.SECONDS);
        } finally {
            release(lock);
            pool.shutdownNow();
        }
    }

    @Test
    void create_withoutRenumbering_doesNotWaitForSiblingRows() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID parent = testData.project(user.id(), "Cha");
        UUID sibling = testData.childProject(user.id(), parent, "S");
        int status = statusWhileHolding(sibling, () -> authPost(user.accessToken(), "/api/v1/projects",
                "{\"name\": \"Moi\", \"parentId\": \"%s\"}".formatted(parent)).andReturn().getResponse().getStatus());
        assertThat(status).isEqualTo(201);
    }

    @Test
    void move_withoutRenumbering_doesNotWaitForSiblingRows() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID parent = testData.project(user.id(), "Cha");
        UUID sibling = testData.childProject(user.id(), parent, "S");
        UUID moving = testData.project(user.id(), "Di chuyen");
        int status = statusWhileHolding(sibling, () -> authPost(user.accessToken(), "/api/v1/projects/" + moving + "/move",
                "{\"parentId\": \"%s\"}".formatted(parent)).andReturn().getResponse().getStatus());
        assertThat(status).isEqualTo(200);
    }

    @Test
    void unarchiveDetach_withoutRenumbering_doesNotWaitForRootSiblingRows() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID rootSibling = testData.project(user.id(), "Goc khac");
        UUID parent = testData.project(user.id(), "Cha");
        UUID child = testData.childProject(user.id(), parent, "Con");
        jdbcTemplate.update("update projects set archived_at = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(120)), parent);
        jdbcTemplate.update("update projects set archived_at = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), child);
        int status = statusWhileHolding(rootSibling, () -> authPost(user.accessToken(),
                "/api/v1/projects/" + child + "/unarchive").andReturn().getResponse().getStatus());
        assertThat(status).isEqualTo(200);
    }

    @Test
    void batchMove_taskMovedElsewhereBeforeLocking_returns409AndDoesNotMoveIt() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        List<UUID> ids = orderedProjects(user.id(), 4);
        UUID p1 = ids.get(0);
        UUID p2 = ids.get(1);
        UUID p3 = ids.get(2); // đích
        UUID z = ids.get(3);
        UUID a = testData.insert(TaskRow.of(user.id(), p2, "a"));
        UUID b = testData.insert(TaskRow.of(user.id(), p1, "b"));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Connection lock = holdLock(p1);
        try {
            Future<Integer> future = pool.submit(() -> authPost(user.accessToken(), "/api/v1/tasks/batch",
                    "{\"action\": \"MOVE\", \"taskIds\": [\"%s\", \"%s\"], \"projectId\": \"%s\"}".formatted(a, b, p3))
                    .andReturn().getResponse().getStatus());
            LockWaits.awaitBlockedBy(jdbcTemplate, LockWaits.backendPid(lock), 1); // đã nạp task, đang chờ khoá p1
            jdbcTemplate.update("update tasks set project_id = ? where id = ?", z, b); // b bị chuyển sang Z (commit)
            release(lock);
            lock = null;
            assertThat(future.get(20, TimeUnit.SECONDS)).isEqualTo(409);
            assertThat(jdbcTemplate.queryForObject("select project_id from tasks where id = ?", UUID.class, b))
                    .as("b vẫn ở Z").isEqualTo(z);
            assertThat(jdbcTemplate.queryForObject("select project_id from tasks where id = ?", UUID.class, a))
                    .as("không chuyển một phần").isEqualTo(p2);
        } finally {
            if (lock != null) {
                release(lock);
            }
            pool.shutdownNow();
        }
    }

    @Test
    void projectMove_locksSiblingsAndItselfAscendingBeforeUpdating() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        List<UUID> ids = orderedProjects(user.id(), 3);
        UUID small = ids.get(0);
        UUID newParent = ids.get(1);
        UUID moving = ids.get(2);
        // `small` là con của newParent (anh em của chỗ đích); move `moving` vào newParent phải khoá small TRƯỚC moving
        // sort_order = 1 + đặt lên ĐẦU (afterId null) → hết khe → phải đánh số lại anh em (chỉ khi đó mới khoá anh em)
        jdbcTemplate.update("update projects set parent_id = ?, sort_order = 1 where id = ?", newParent, small);
        int status = runBlockedBy(small, () -> authPost(user.accessToken(), "/api/v1/projects/" + moving + "/move",
                "{\"parentId\": \"%s\", \"afterId\": null}".formatted(newParent)).andReturn().getResponse().getStatus(),
                moving);
        assertThat(status).isEqualTo(200);
    }

    @Test
    void batchMove_locksAllSourcesAndTargetAscendingBeforeMovingAnyRoot() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        List<UUID> ids = orderedProjects(user.id(), 3);
        UUID p1 = ids.get(0);
        UUID p2 = ids.get(1);
        UUID p3 = ids.get(2); // đích
        UUID a = testData.insert(TaskRow.of(user.id(), p2, "a"));
        UUID b = testData.insert(TaskRow.of(user.id(), p1, "b"));
        int status = runBlockedBy(p1, () -> authPost(user.accessToken(), "/api/v1/tasks/batch",
                "{\"action\": \"MOVE\", \"taskIds\": [\"%s\", \"%s\"], \"projectId\": \"%s\"}".formatted(a, b, p3))
                .andReturn().getResponse().getStatus(), p2, p3);
        assertThat(status).isEqualTo(200);
    }
}
