package com.taskly.support;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Chờ request đang bị chặn BỞI một kết nối giữ khoá cụ thể (không đếm mọi backend đang chờ khoá trong DB): đếm các
 * backend chờ trực tiếp hoặc gián tiếp (qua chuỗi chờ) kết nối `holderPid`.
 */
public final class LockWaits {

    private LockWaits() {
    }

    public static int backendPid(Connection connection) throws Exception {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("select pg_backend_pid()")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** Chờ tới khi ít nhất `expected` backend bị chặn (kể cả gián tiếp) bởi holderPid; tối đa ~10 s. */
    public static void awaitBlockedBy(JdbcTemplate jdbc, int holderPid, int expected) throws Exception {
        for (int i = 0; i < 200; i++) {
            Integer waiting = jdbc.queryForObject("""
                    with recursive w(pid) as (
                      select pid from pg_stat_activity where ? = any(pg_blocking_pids(pid))
                      union
                      select a.pid from pg_stat_activity a join w on w.pid = any(pg_blocking_pids(a.pid))
                    ) select count(*) from w""", Integer.class, holderPid);
            if (waiting != null && waiting >= expected) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("không đủ " + expected + " request đang chờ khoá do kết nối " + holderPid);
    }
}
