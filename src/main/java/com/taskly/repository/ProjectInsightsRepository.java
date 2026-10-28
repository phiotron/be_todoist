package com.taskly.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** SQL đếm cho Insights của dự án (M10 plan F6). Chỉ task của CHÍNH dự án (không dự án con). */
@Repository
public class ProjectInsightsRepository {

    public record Counts(int total, int completed, int overdue) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public ProjectInsightsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** overdue = đang mở và due_day < today (khối Quá hạn của M1). */
    public Counts counts(UUID projectId, LocalDate today) {
        return jdbc.queryForObject("""
                select count(*) as total,
                       count(*) filter (where completed_at is not null) as completed,
                       count(*) filter (where completed_at is null and due_day < :today) as overdue
                from tasks where project_id = :projectId
                """, new MapSqlParameterSource().addValue("projectId", projectId).addValue("today", today),
                (rs, i) -> new Counts(rs.getInt("total"), rs.getInt("completed"), rs.getInt("overdue")));
    }

    /** Số task hoàn thành theo ngày (múi giờ timeZone) cho task có completed_at ≥ since. */
    public Map<LocalDate, Integer> completedByDay(UUID projectId, Instant since, String timeZone) {
        Map<LocalDate, Integer> result = new HashMap<>();
        jdbc.query("""
                select cast(completed_at at time zone :tz as date) as day, count(*) as n
                from tasks
                where project_id = :projectId and completed_at >= :since
                group by 1
                """, new MapSqlParameterSource()
                        .addValue("projectId", projectId)
                        .addValue("since", Timestamp.from(since))
                        .addValue("tz", timeZone),
                rs -> {
                    result.put(rs.getObject("day", LocalDate.class), rs.getInt("n"));
                });
        return result;
    }
}
