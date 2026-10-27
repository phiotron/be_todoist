package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.IntegrationTestBase;
import com.taskly.support.LockWaits;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
 * reorder PROJECT cùng quy tắc khoá với create / move / archive dự án: lấy mutex workspace (W) trước, rồi khoá hàng
 * dự án id TĂNG DẦN trước khi ghi sort_order. Không có W → reorder ghi đè sort_order do create / move vừa đánh số lại
 * (mất cập nhật); ghi theo thứ tự nạp → khoá chéo với create / move (40P01 → 409 giả).
 */
class ProjectReorderLockApiTest extends IntegrationTestBase {

    @Autowired
    private DataSource dataSource;

    private Connection hold(String table, UUID id) throws Exception {
        Connection c = dataSource.getConnection();
        c.setAutoCommit(false);
        try (PreparedStatement ps = c.prepareStatement("select id from " + table + " where id = ? for no key update")) {
            ps.setObject(1, id);
            ps.executeQuery().close();
        }
        return c;
    }

    private boolean lockedByOthers(UUID id) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("select id from projects where id = ? for no key update skip locked")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return !rs.next();
            }
        }
    }

    private String reorderBody(UUID id, UUID afterId) {
        return "{\"type\": \"PROJECT\", \"id\": \"%s\", \"afterId\": \"%s\"}".formatted(id, afterId);
    }

    @Test
    void reorderProject_waitsForWorkspaceMutex() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID a = testData.project(user.id(), "A");
        UUID b = testData.project(user.id(), "B");
        UUID workspace = testData.workspaceOf(user.id());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Connection w = hold("workspaces", workspace);
        try {
            Future<Integer> reorder = pool.submit(() -> authPost(user.accessToken(), "/api/v1/reorder",
                    reorderBody(b, a)).andReturn().getResponse().getStatus());
            LockWaits.awaitBlockedBy(jdbcTemplate, LockWaits.backendPid(w), 1); // phải bị chặn bởi W của thao tác cấu trúc khác
            w.rollback();
            w.close();
            w = null;
            assertThat(reorder.get(20, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            if (w != null) {
                w.rollback();
                w.close();
            }
            pool.shutdownNow();
        }
    }

    @Test
    void reorderProject_renumbering_locksRowsAscendingBeforeUpdating() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ids.add(testData.project(user.id(), "P" + i));
        }
        UUID min = ids.stream().min(Comparator.naturalOrder()).orElseThrow();
        List<UUID> others = ids.stream().filter(id -> !id.equals(min)).toList();
        UUID moved = others.get(0);
        // anh em theo thứ tự: others[1], others[2], min (id nhỏ nhất đứng CUỐI thứ tự nạp); khoảng trống 1 → phải đánh số lại
        List<UUID> siblings = List.of(others.get(1), others.get(2), min);
        for (int i = 0; i < siblings.size(); i++) {
            jdbcTemplate.update("update projects set sort_order = ? where id = ?", i + 1, siblings.get(i));
        }
        jdbcTemplate.update("update projects set sort_order = 100000 where id = ?", moved);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Connection held = hold("projects", min);
        try {
            Future<Integer> reorder = pool.submit(() -> authPost(user.accessToken(), "/api/v1/reorder",
                    reorderBody(moved, siblings.get(0))).andReturn().getResponse().getStatus());
            LockWaits.awaitBlockedBy(jdbcTemplate, LockWaits.backendPid(held), 1);
            for (UUID id : others) {
                assertThat(lockedByOthers(id)).as("request đã giữ %s khi còn chờ khoá id nhỏ nhất", id).isFalse();
            }
            held.rollback();
            held.close();
            held = null;
            assertThat(reorder.get(20, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            if (held != null) {
                held.rollback();
                held.close();
            }
            pool.shutdownNow();
        }
    }
}
