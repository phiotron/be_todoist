package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.IntegrationTestBase;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tạo task đua lưu trữ section (spec 8.2 #9): giữ khoá dự án trong một transaction để ép thứ tự xác định.
 * Create đã qua mọi kiểm tra rồi chờ khoá → khi vào được phải ĐỌC LẠI cờ lưu trữ; archive phải chờ cùng khoá dự án.
 */
class SectionArchiveRaceApiTest extends IntegrationTestBase {

    @Autowired
    private PlatformTransactionManager transactionManager;

    private void awaitBlockedOnLock() throws Exception {
        for (int i = 0; i < 100; i++) {
            Integer waiting = jdbcTemplate.queryForObject(
                    "select count(*) from pg_stat_activity where wait_event_type = 'Lock' and datname = current_database()",
                    Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("không có request nào đang chờ khoá");
    }

    @Test
    void createWaitingOnProjectLock_rechecksSectionArchivedAfterLock() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        UUID section = testData.section(project, "S");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> create = new TransactionTemplate(transactionManager).execute(status -> {
                jdbcTemplate.queryForList("select id from projects where id = ? for no key update", UUID.class, project);
                Future<Integer> f = pool.submit(() -> authPost(user.accessToken(), "/api/v1/tasks",
                        "{\"projectId\": \"%s\", \"sectionId\": \"%s\", \"content\": \"Đua\"}".formatted(project, section))
                        .andReturn().getResponse().getStatus());
                try {
                    awaitBlockedOnLock();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                testData.archiveSection(section); // "archive" thắng khoá, commit khi thoát transaction
                return f;
            });

            assertThat(create.get(20, TimeUnit.SECONDS)).isEqualTo(422);
            assertThat(jdbcTemplate.queryForObject("select count(*) from tasks where section_id = ?", Integer.class,
                    section)).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void archiveSection_waitsForProjectLock() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        UUID section = testData.section(project, "S");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> archive = new TransactionTemplate(transactionManager).execute(status -> {
                jdbcTemplate.queryForList("select id from projects where id = ? for no key update", UUID.class, project);
                Future<Integer> f = pool.submit(() -> authPost(user.accessToken(),
                        "/api/v1/projects/" + project + "/sections/" + section + "/archive")
                        .andReturn().getResponse().getStatus());
                try {
                    awaitBlockedOnLock();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return f;
            });

            assertThat(archive.get(20, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
    }
}
