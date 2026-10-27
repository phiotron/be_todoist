package com.taskly.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.IntegrationTestBase;
import com.taskly.common.error.ApiException;
import com.taskly.common.error.ProblemTexts;
import com.taskly.entity.LimitKind;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M2 dùng lại lockUser của M1 (FOR NO KEY UPDATE, bắt buộc có transaction) + trần label / filter + label mỗi task
 * (spec 2.1, 8.2 #9, 14.1). Test sâu về khoá nằm ở UsageLimitsLockTest (M1).
 */
class UsageLimitsM2Test extends IntegrationTestBase {

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void lockUser_outsideTransaction_failsFast() {
        assertThatThrownBy(() -> usageLimits.lockUser(UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void lockUser_blocksAnotherTransactionUntilTheFirstCommits() throws Exception {
        UUID user = registerUser(uniqueEmail()).id();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> holder = pool.submit(() -> transactions.executeWithoutResult(status -> {
            usageLimits.lockUser(user);
            locked.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }));
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            // giao dịch thứ hai không lấy được khoá trong 300 ms → Postgres 55P03 (lock_not_available)
            assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                jdbcTemplate.execute("SET LOCAL lock_timeout = '300ms'");
                usageLimits.lockUser(user);
            })).isInstanceOf(DataAccessException.class);
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            pool.shutdownNow();
        }
        // giao dịch đầu đã commit → khoá được ngay
        transactions.executeWithoutResult(status -> usageLimits.lockUser(user));
    }

    @Test
    void labelAndFilterCeilings_useTheM1Check_with422CodeLimitAndMax() {
        usageLimits.overrideMax(LimitKind.LABELS, 5);
        usageLimits.overrideMax(LimitKind.FILTERS, 3);
        usageLimits.check(LimitKind.LABELS, 4, 1);
        usageLimits.check(LimitKind.FILTERS, 2, 1);

        assertThatThrownBy(() -> usageLimits.check(LimitKind.LABELS, 5, 1))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(422);
                    assertThat(ex.getBody().getProperties()).containsEntry("code", "limit-reached")
                            .containsEntry("limit", "LABELS").containsEntry("max", 5);
                });
        assertThatThrownBy(() -> usageLimits.check(LimitKind.FILTERS, 3, 1))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(422);
                    assertThat(ex.getBody().getProperties()).containsEntry("code", "limit-reached")
                            .containsEntry("limit", "FILTERS").containsEntry("max", 3);
                });
    }

    @Test
    void labelsPerTask_overLimitIs400WithMax() {
        usageLimits.checkLabelsPerTask(3);
        assertThat(usageLimits.maxLabelsPerTask()).isEqualTo(3);

        assertThatThrownBy(() -> usageLimits.checkLabelsPerTask(4)).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getStatusCode().value()).isEqualTo(400);
            assertThat(ex.getBody().getDetail()).isEqualTo(ProblemTexts.LABELS_PER_TASK);
            assertThat(ex.getBody().getProperties()).containsEntry("max", 3);
        });
    }
}
