package com.taskly.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * tasks/batch CLOSE nhiều task gốc cùng một dự án: số câu lệnh KHÔNG được tăng theo kiểu "mỗi task gốc đọc lại cả cây +
 * kiểm quyền lại" (O(N) lần đọc cây). Đo bằng Hibernate Statistics (bật tạm trong test).
 */
class TaskBatchStatementCountTest extends IntegrationTestBase {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private long closeStatements(RegisteredUser user, int roots) throws Exception {
        UUID project = testData.project(user.id(), "P" + roots);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < roots; i++) {
            UUID root = testData.task(user.id(), project, "R" + i);
            testData.insert(com.taskly.support.TestData.TaskRow.of(user.id(), project, "c" + i).parent(root));
            ids.add("\"" + root + "\"");
        }
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        // Statistics là toàn cục (đếm cả luồng nền như IdempotencyCleanupJob) → chỉ so SỐ GIA giữa hai cỡ lô với ngưỡng
        // có biên rộng, và luôn tắt lại trong finally.
        try {
            statistics.setStatisticsEnabled(true);
            statistics.clear();
            authPost(user.accessToken(), "/api/v1/tasks/batch",
                    "{\"action\": \"CLOSE\", \"taskIds\": [%s]}".formatted(String.join(",", ids)))
                    .andExpect(status().isOk());
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    @Test
    void batchCloseOfManyRoots_doesNotReloadTheTreeAndMembershipPerRoot() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        long few = closeStatements(user, 2);
        long many = closeStatements(user, 12);

        // mỗi task gốc thêm vào chỉ còn tải entity + UPDATE (đo được ~3 câu / gốc = 30 cho 10 gốc thêm), không còn cây + quyền (trước: ~10 câu / gốc = 100+); ngưỡng 60 chừa biên cho câu lệnh luồng nền
        assertThat(many - few).as("few=%d many=%d", few, many).isLessThanOrEqualTo(60L);
    }
}
