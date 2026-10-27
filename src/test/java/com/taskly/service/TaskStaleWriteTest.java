package com.taskly.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.IntegrationTestBase;
import com.taskly.entity.Task;
import com.taskly.repository.TaskRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * UPDATE qua entity chỉ ghi các cột ĐÃ ĐỔI (@DynamicUpdate): relocate (native, không tăng version) của một move song
 * song không bị PATCH / close kéo lùi project_id / section_id của con (spec 5.6).
 */
class TaskStaleWriteTest extends IntegrationTestBase {

    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private TaskService taskService;

    @Test
    void entityUpdateOfStaleChild_doesNotRevertRelocatedProject() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID projectX = testData.project(user.id(), "X");
        UUID projectY = testData.project(user.id(), "Y");
        UUID child = testData.task(user.id(), projectX, "Con");
        UUID workspace = testData.workspaceOf(user.id());
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> {
            Task stale = taskRepository.findById(child).orElseThrow(); // đã nạp: project_id = X
            runInOtherTransaction(() -> taskService.relocate(List.of(child), projectY, null, workspace));
            stale.setContent("Sửa nội dung"); // dirty chỉ content
        });

        assertThat(jdbcTemplate.queryForObject("select project_id from tasks where id = ?", UUID.class, child))
                .isEqualTo(projectY);
        assertThat(jdbcTemplate.queryForObject("select content from tasks where id = ?", String.class, child))
                .isEqualTo("Sửa nội dung");
    }

    private void runInOtherTransaction(Runnable action) {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> action.run()))
                    .get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            pool.shutdownNow();
        }
    }
}
