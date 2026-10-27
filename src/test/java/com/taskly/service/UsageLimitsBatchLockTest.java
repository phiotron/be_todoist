package com.taskly.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.IntegrationTestBase;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * lockProjects khoá cả mảng bằng MỘT câu lệnh (xoá / lưu trữ cây 500 dự án giữ W trong lúc khoá: trước đây 500 lượt
 * khứ hồi). Hành vi khoá (tăng dần, chặn đúng, không deadlock) do UsageLimitsLockTest + ProjectLockOrderApiTest giữ.
 */
class UsageLimitsBatchLockTest extends IntegrationTestBase {

    @Autowired
    private DataSource dataSource;

    @Test
    void lockProjects_issuesOneStatementForTheWholeBatch_andLocksEveryRow() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            ids.add(testData.project(user.id(), "P" + i));
        }
        AtomicInteger statements = new AtomicInteger();
        UsageLimits limits = new UsageLimits(300, 500, 300, 20, 100, 500, 150, 700);
        List<UUID> withDuplicate = new ArrayList<>(ids);
        withDuplicate.add(ids.get(0));

        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // gọi thẳng đối tượng không qua proxy @Transactional: MANDATORY không áp dụng, nên chạy trên kết nối riêng
            // bằng JdbcTemplate bọc kết nối đó
            java.sql.Connection counting = (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {java.sql.Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement") || method.getName().equals("createStatement")) {
                            statements.incrementAndGet();
                        }
                        try {
                            return method.invoke(connection, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            JdbcTemplate onConnection = new JdbcTemplate(
                    new org.springframework.jdbc.datasource.SingleConnectionDataSource(counting, true));
            limits.setJdbcTemplate(onConnection);
            limits.lockProjects(withDuplicate);

            assertThat(statements.get()).as("số câu lệnh khoá").isEqualTo(1);
            for (UUID id : ids) {
                try (var other = dataSource.getConnection();
                     PreparedStatement ps = other.prepareStatement(
                             "select id from projects where id = ? for no key update skip locked")) {
                    ps.setObject(1, id);
                    try (var rs = ps.executeQuery()) {
                        assertThat(rs.next()).as("hàng %s phải đang bị khoá", id).isFalse();
                    }
                }
            }
            connection.rollback();
        }
    }
}
