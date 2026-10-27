package com.taskly.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.IntegrationTestBase;
import com.taskly.common.error.ApiException;
import com.taskly.entity.LimitKind;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Khoá hàng cha chống đua đếm-rồi-chèn (spec 8.2 #9, C5): SELECT … FOR NO KEY UPDATE (không xung đột với FOR KEY SHARE
 * của khoá ngoại khi chèn hàng con → không deadlock chéo). Test đua đầu-cuối qua API: Part 3 `LimitRaceApiTest`.
 */
class UsageLimitsLockTest extends IntegrationTestBase {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    // ---- Khoá chặn giao dịch khác ----

    @Test
    void lockProject_blocksAnotherTransactionUntilTheFirstOneCommits() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");

        assertSecondTransactionWaits(() -> usageLimits.lockProject(project), () -> usageLimits.lockProject(project));
    }

    @Test
    void lockWorkspace_blocksAnotherTransactionUntilTheFirstOneCommits() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID workspace = testData.workspaceOf(user.id());

        assertSecondTransactionWaits(() -> usageLimits.lockWorkspace(workspace),
                () -> usageLimits.lockWorkspace(workspace));
    }

    @Test
    void lockUser_blocksAnotherTransactionUntilTheFirstOneCommits() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        assertSecondTransactionWaits(() -> usageLimits.lockUser(user.id()), () -> usageLimits.lockUser(user.id()));
    }

    private void assertSecondTransactionWaits(Runnable firstLock, Runnable secondLock) throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch firstHoldsLock = new CountDownLatch(1);
        AtomicLong firstAboutToCommit = new AtomicLong();
        AtomicLong secondStarted = new AtomicLong();
        AtomicLong secondAcquired = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> tx.executeWithoutResult(status -> {
                firstLock.run();
                firstHoldsLock.countDown();
                sleep(400);
                firstAboutToCommit.set(System.nanoTime());
            }));
            Future<?> second = pool.submit(() -> {
                firstHoldsLock.await();
                tx.executeWithoutResult(status -> {
                    secondStarted.set(System.nanoTime());
                    secondLock.run(); // chờ tới khi transaction đầu commit
                    secondAcquired.set(System.nanoTime());
                });
                return null;
            });
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(secondAcquired.get()).isGreaterThanOrEqualTo(firstAboutToCommit.get());
        assertThat(Duration.ofNanos(secondAcquired.get() - secondStarted.get())).isGreaterThan(Duration.ofMillis(200));
    }

    @Test
    void lockProjects_lockInAscendingIdOrder_soOppositeArgumentOrdersDoNotDeadlock() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID a = testData.project(user.id(), "A");
        UUID b = testData.project(user.id(), "B");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> forward = pool.submit(() -> {
                for (int i = 0; i < 25; i++) {
                    tx.executeWithoutResult(status -> usageLimits.lockProjects(List.of(a, b)));
                }
            });
            Future<?> backward = pool.submit(() -> {
                for (int i = 0; i < 25; i++) {
                    tx.executeWithoutResult(status -> usageLimits.lockProjects(List.of(b, a, b)));
                }
            });
            forward.get(60, TimeUnit.SECONDS); // quá hạn / ném DeadlockLoser = deadlock
            backward.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void lockOutsideATransaction_isRejected() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");

        assertThatThrownBy(() -> usageLimits.lockProject(project)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> usageLimits.lockProjects(List.of(project)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> usageLimits.lockWorkspace(testData.workspaceOf(user.id())))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> usageLimits.lockUser(user.id())).isInstanceOf(IllegalTransactionStateException.class);
    }

    // ---- Khoá nằm trong CÙNG connection / transaction với JPA (JdbcTemplate + JpaTransactionManager) ----

    @Test
    void lock_isHeldOnTheSameConnectionAsJpaAndReleasedOnCommit() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        ExecutorService otherConnection = Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(status -> {
                usageLimits.lockProject(project);

                Number jdbcPid = jdbcTemplate.queryForObject("select pg_backend_pid()", Integer.class);
                Number jpaPid = (Number) entityManager.createNativeQuery("select pg_backend_pid()").getSingleResult();
                assertThat(jpaPid.intValue()).isEqualTo(jdbcPid.intValue());

                // một connection khác (thread khác, không có transaction) không lấy được khoá ngay
                assertThatThrownBy(() -> inOtherThread(otherConnection, () -> tryLockNoWait(project)))
                        .rootCause()
                        .isInstanceOf(SQLException.class)
                        .hasFieldOrPropertyWithValue("SQLState", "55P03"); // lock_not_available
            });

            // sau commit khoá đã nhả
            assertThat(inOtherThread(otherConnection, () -> tryLockNoWait(project))).isEqualTo(project);
        } finally {
            otherConnection.shutdownNow();
        }
    }

    private UUID tryLockNoWait(UUID projectId) {
        return jdbcTemplate.queryForObject("select id from projects where id = ? for no key update nowait",
                UUID.class, projectId);
    }

    private static <T> T inOtherThread(ExecutorService pool, Callable<T> task) throws Exception {
        try {
            return pool.submit(task).get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    // ---- FOR NO KEY UPDATE: không xung đột với FOR KEY SHARE của khoá ngoại khi chèn hàng con ----

    @Test
    void lockProject_doesNotBlockInsertingChildRowsThatReferenceTheProject() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                usageLimits.lockProject(project);
                holding.countDown();
                await(release);
            }));
            holding.await();
            Future<?> inserter = pool.submit(() -> tx.executeWithoutResult(status -> {
                jdbcTemplate.execute("set local lock_timeout = '3s'"); // chờ khoá = lỗi (FOR UPDATE sẽ chặn ở đây)
                testData.section(project, "S");
                testData.task(user.id(), project, "T");
                testData.childProject(user.id(), project, "C");
            }));
            inserter.get(15, TimeUnit.SECONDS);
            release.countDown();
            holder.get(15, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject("select count(*) from tasks where project_id = ?", Long.class, project))
                .isEqualTo(1L);
    }

    @Test
    void crossLocks_projectThenInsertTask_versusWorkspaceThenInsertChildProject_doNotDeadlock() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID workspace = testData.workspaceOf(user.id());
        UUID project = testData.project(user.id(), "P");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch bothLocked = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // A: khoá project P rồi chèn task (cần KEY SHARE trên workspace W — B đang khoá W)
            Future<?> a = pool.submit(() -> tx.executeWithoutResult(status -> {
                usageLimits.lockProject(project);
                bothLocked.countDown();
                await(bothLocked);
                testData.task(user.id(), project, "T");
            }));
            // B: khoá workspace W rồi chèn dự án con của P (cần KEY SHARE trên P — A đang khoá P)
            Future<?> b = pool.submit(() -> tx.executeWithoutResult(status -> {
                usageLimits.lockWorkspace(workspace);
                bothLocked.countDown();
                await(bothLocked);
                testData.childProject(user.id(), project, "C");
            }));
            a.get(30, TimeUnit.SECONDS); // FOR UPDATE: một bên nhận DeadlockLoserDataAccessException
            b.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- Đua: ở mức max − 1 chỉ đúng một thành công, không vượt giới hạn ----

    @Test
    void race_projectSections_neverExceedTheLimit() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        usageLimits.overrideMax(LimitKind.PROJECT_SECTIONS, 5);

        Outcome outcome = race(8, () -> {
            usageLimits.lockProject(project);
            long count = jdbcTemplate.queryForObject("select count(*) from sections where project_id = ?",
                    Long.class, project);
            usageLimits.check(LimitKind.PROJECT_SECTIONS, count, 1);
            testData.section(project, "S");
        });

        assertThat(outcome.unexpected()).isEmpty();
        assertThat(outcome.created()).isEqualTo(5);
        assertThat(outcome.limitReached()).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("select count(*) from sections where project_id = ?", Long.class,
                project)).isEqualTo(5L);
    }

    @Test
    void race_projectsOfAWorkspace_neverExceedTheLimit() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID workspace = testData.workspaceOf(user.id());
        long existing = jdbcTemplate.queryForObject("select count(*) from projects where workspace_id = ?",
                Long.class, workspace);
        usageLimits.overrideMax(LimitKind.PROJECTS, (int) existing + 3);

        Outcome outcome = race(8, () -> {
            usageLimits.lockWorkspace(workspace);
            long count = jdbcTemplate.queryForObject("select count(*) from projects where workspace_id = ?",
                    Long.class, workspace);
            usageLimits.check(LimitKind.PROJECTS, count, 1);
            testData.project(user.id(), "P");
        });

        assertThat(outcome.unexpected()).isEmpty();
        assertThat(outcome.created()).isEqualTo(3);
        assertThat(outcome.limitReached()).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject("select count(*) from projects where workspace_id = ?", Long.class,
                workspace)).isEqualTo(existing + 3);
    }

    @Test
    void race_perUserCount_neverExceedsTheLimit() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        // M2 chưa có bảng labels: dùng favorites (target_type = LABEL) làm đại diện cho "đếm theo tài khoản"
        usageLimits.overrideMax(LimitKind.LABELS, 4);

        Outcome outcome = race(8, () -> {
            usageLimits.lockUser(user.id());
            long count = jdbcTemplate.queryForObject(
                    "select count(*) from favorites where user_id = ? and target_type = 'LABEL'", Long.class, user.id());
            usageLimits.check(LimitKind.LABELS, count, 1);
            testData.favorite(user.id(), "LABEL", UUID.randomUUID());
        });

        assertThat(outcome.unexpected()).isEmpty();
        assertThat(outcome.created()).isEqualTo(4);
        assertThat(outcome.limitReached()).isEqualTo(4);
    }

    @Test
    void race_mixedWritersOnTheSameProject_stillRespectTheLimit() throws Exception {
        // Thêm task + thêm section cùng dự án, cùng khoá lockProject: không ai ném lỗi lạ (deadlock / timeout).
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        usageLimits.overrideMax(LimitKind.PROJECT_ACTIVE_TASKS, 3);
        AtomicInteger turn = new AtomicInteger();

        Outcome outcome = race(8, () -> {
            usageLimits.lockProject(project);
            if (turn.getAndIncrement() % 2 == 0) {
                long count = jdbcTemplate.queryForObject(
                        "select count(*) from tasks where project_id = ? and completed_at is null", Long.class, project);
                usageLimits.check(LimitKind.PROJECT_ACTIVE_TASKS, count, 1);
                testData.task(user.id(), project, "T");
            } else {
                testData.section(project, "S");
            }
        });

        assertThat(outcome.unexpected()).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from tasks where project_id = ? and completed_at is null", Long.class, project))
                .isEqualTo(3L);
    }

    /** Chạy body trong một transaction ở mỗi thread, tất cả xuất phát cùng lúc; phân loại kết quả. */
    private Outcome race(int threads, Runnable body) throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CyclicBarrier start = new CyclicBarrier(threads);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger limitReached = new AtomicInteger();
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        start.await(30, TimeUnit.SECONDS);
                        tx.executeWithoutResult(status -> body.run());
                        created.incrementAndGet();
                    } catch (ApiException ex) {
                        if (ex.getStatusCode().value() == 422
                                && "limit-reached".equals(ex.getBody().getProperties().get("code"))) {
                            limitReached.incrementAndGet();
                        } else {
                            unexpected.add(ex);
                        }
                    } catch (Throwable t) {
                        unexpected.add(t);
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        return new Outcome(created.get(), limitReached.get(), unexpected);
    }

    private record Outcome(int created, int limitReached, List<Throwable> unexpected) {
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
