package com.taskly.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.IntegrationTestBase;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/** Điều kiện khớp chữ của search PHẢI dùng được chỉ mục trigram V3 (quyết định plan #1), không chỉ đúng kết quả. */
class TaskSearchRepositoryTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    private String explain(String condition) {
        return jdbc.execute((ConnectionCallback<String>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET enable_seqscan = off");
                statement.execute("SET enable_indexscan = off");
                statement.execute("SET enable_indexonlyscan = off");
                try (ResultSet rs = statement.executeQuery("EXPLAIN SELECT t.id FROM tasks t WHERE " + condition)) {
                    StringBuilder text = new StringBuilder();
                    while (rs.next()) {
                        text.append(rs.getString(1)).append('\n');
                    }
                    return text.toString();
                } finally {
                    statement.execute("RESET enable_seqscan");
                    statement.execute("RESET enable_indexscan");
                    statement.execute("RESET enable_indexonlyscan");
                }
            }
        });
    }

    @Test
    void contentCondition_usesTrigramIndex() {
        String condition = TaskSearchRepository.contains("t.content", 0).replace(":term0", "'%hop%'");
        assertThat(explain(condition)).contains("ix_tasks_content_trgm");
    }

    @Test
    void descriptionCondition_usesTrigramIndex() {
        String condition = TaskSearchRepository.contains("t.description", 0).replace(":term0", "'%hop%'");
        assertThat(explain(condition)).contains("ix_tasks_description_trgm");
    }
}
