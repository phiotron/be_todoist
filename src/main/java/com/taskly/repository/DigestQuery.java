package com.taskly.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Số liệu cho email Morning overview / Evening review (plan M13c, F7): task chưa xong đến hạn hôm nay hoặc quá hạn,
 * số task đã xong hôm nay, số task đến hạn ngày mai. Phạm vi như nhắc việc: workspace đọc được, dự án / section chưa
 * lưu trữ. "Hôm nay" do người gọi tính theo múi giờ tài khoản.
 */
@Repository
public class DigestQuery {

    public record DigestTask(String content, LocalDate dueDay) {
    }

    private static final String OPEN_SCOPE = """
            FROM tasks t
            JOIN projects p ON p.id = t.project_id
            LEFT JOIN sections s ON s.id = t.section_id
            WHERE t.workspace_id IN (:workspaceIds) AND t.completed_at IS NULL
              AND p.archived_at IS NULL AND (s.id IS NULL OR s.archived_at IS NULL)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public DigestQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Task đến hạn ≤ today (gồm quá hạn), sắp theo hạn rồi ưu tiên; tối đa limit dòng. */
    public List<DigestTask> openDueUpTo(Collection<java.util.UUID> workspaceIds, LocalDate today, int limit) {
        if (workspaceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT t.content, t.due_day " + OPEN_SCOPE
                        + " AND t.due_day <= :today ORDER BY t.due_day, t.due_sort, t.priority, t.id LIMIT :limit",
                params(workspaceIds).addValue("today", today).addValue("limit", limit),
                (rs, n) -> new DigestTask(rs.getString("content"), rs.getObject("due_day", LocalDate.class)));
    }

    public int countOpenDueUpTo(Collection<java.util.UUID> workspaceIds, LocalDate today) {
        if (workspaceIds.isEmpty()) {
            return 0;
        }
        Integer count = jdbc.queryForObject("SELECT count(*) " + OPEN_SCOPE + " AND t.due_day <= :today",
                params(workspaceIds).addValue("today", today), Integer.class);
        return count == null ? 0 : count;
    }

    public int countOpenDueOn(Collection<java.util.UUID> workspaceIds, LocalDate day) {
        if (workspaceIds.isEmpty()) {
            return 0;
        }
        Integer count = jdbc.queryForObject("SELECT count(*) " + OPEN_SCOPE + " AND t.due_day = :day",
                params(workspaceIds).addValue("day", day), Integer.class);
        return count == null ? 0 : count;
    }

    /** Task hoàn thành trong [from, to). */
    public int countCompletedBetween(Collection<java.util.UUID> workspaceIds, Instant from, Instant to) {
        if (workspaceIds.isEmpty()) {
            return 0;
        }
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM tasks t
                WHERE t.workspace_id IN (:workspaceIds) AND t.completed_at >= :from AND t.completed_at < :to
                """, params(workspaceIds).addValue("from", SqlTime.ts(from)).addValue("to", SqlTime.ts(to)),
                Integer.class);
        return count == null ? 0 : count;
    }

    private static MapSqlParameterSource params(Collection<java.util.UUID> workspaceIds) {
        return new MapSqlParameterSource("workspaceIds", workspaceIds);
    }
}
