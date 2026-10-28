package com.taskly.repository;

import com.taskly.security.ReadScope;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Đếm task đang mở theo due_day (M6b, Date sheet). Cùng điều kiện "đang mở trong dự án chưa lưu trữ" với view Today /
 * Upcoming (TaskViewService); chạy trên chỉ mục ix_tasks_workspace_due (workspace_id, due_day) WHERE completed_at IS NULL.
 */
@Repository
public class TaskDayCountRepository {

    private static final String SQL = """
            select t.due_day as day, count(*) as total
            from tasks t
            join projects p on p.id = t.project_id
            where t.workspace_id in (:workspaceIds)
              and t.project_id in (:projectIds)
              and t.completed_at is null
              and p.archived_at is null
              and t.due_day between :from and :to
            group by t.due_day
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public TaskDayCountRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Chỉ những ngày có ít nhất một task; phạm vi rỗng → rỗng. M8a: phạm vi = dự án đã tham gia (ReadScope). */
    public Map<LocalDate, Integer> countOpenByDueDay(ReadScope scope, LocalDate from, LocalDate to) {
        Map<LocalDate, Integer> counts = new HashMap<>();
        if (scope.isEmpty()) {
            return counts;
        }
        MapSqlParameterSource params = scope.params()
                .addValue("from", from)
                .addValue("to", to);
        jdbc.query(SQL, params, rs -> {
            counts.put(rs.getObject("day", LocalDate.class), rs.getInt("total"));
        });
        return counts;
    }
}
