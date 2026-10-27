package com.taskly.repository;

import com.taskly.entity.ReminderKind;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Lịch nhắc sắp tới (spec 5.2, 5.8, 12.8) — SQL tay chỉ đọc, như TaskViewRepository của M1. Công thức fireAt nằm ở
 * đây (một chỗ): AUTO = due_datetime − users.auto_reminder_minutes (chỉ task có giờ, cài đặt khác null);
 * RELATIVE = due_datetime − minute_offset; ABSOLUTE = at. Mọi giá trị là giờ floating, so với :now (bây giờ theo
 * múi giờ tài khoản). Phạm vi: task chưa xong trong workspace đọc được, dự án / section chưa lưu trữ; nhắc riêng chỉ
 * của chính user. Bỏ trùng cùng (task, fireAt): nhắc riêng trước auto, rồi nhắc tạo sớm hơn, rồi id.
 */
@Repository
public class ReminderOccurrenceQuery {

    private static final String SQL = """
            WITH scope AS (
                SELECT t.id, t.content, t.due_datetime
                FROM tasks t
                JOIN projects p ON p.id = t.project_id
                LEFT JOIN sections s ON s.id = t.section_id
                WHERE t.workspace_id IN (:workspaceIds)
                  AND t.completed_at IS NULL
                  AND p.archived_at IS NULL
                  AND (s.id IS NULL OR s.archived_at IS NULL)
            ),
            auto_setting AS (
                SELECT u.auto_reminder_minutes AS minutes FROM users u WHERE u.id = :userId
            ),
            occurrences AS (
                SELECT sc.id AS task_id, CAST(NULL AS uuid) AS reminder_id, 'AUTO' AS kind,
                       sc.due_datetime - make_interval(mins => a.minutes) AS fire_at,
                       sc.content, 1 AS rank, CAST(NULL AS timestamptz) AS created_at
                FROM scope sc
                CROSS JOIN auto_setting a
                WHERE a.minutes IS NOT NULL AND sc.due_datetime IS NOT NULL
                UNION ALL
                SELECT sc.id, r.id, r.type,
                       CASE WHEN r.type = 'ABSOLUTE' THEN r.at
                            ELSE sc.due_datetime - make_interval(mins => r.minute_offset) END,
                       sc.content, 0, r.created_at
                FROM reminders r
                JOIN scope sc ON sc.id = r.task_id
                WHERE r.user_id = :userId
                  AND (r.type = 'ABSOLUTE' OR sc.due_datetime IS NOT NULL)
            ),
            deduped AS (
                SELECT DISTINCT ON (task_id, fire_at) task_id, reminder_id, kind, fire_at, content
                FROM occurrences
                WHERE fire_at >= :now
                ORDER BY task_id, fire_at, rank, created_at, reminder_id
            )
            SELECT task_id, reminder_id, kind, fire_at, content
            FROM deduped
            ORDER BY fire_at, task_id
            LIMIT :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ReminderOccurrenceQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ReminderOccurrenceRow> find(UUID userId, Collection<UUID> workspaceIds, LocalDateTime now, int limit) {
        if (workspaceIds.isEmpty()) {
            return List.of(); // "IN ()" không hợp lệ trong SQL
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("workspaceIds", workspaceIds)
                .addValue("now", now)
                .addValue("limit", limit);
        return jdbc.query(SQL, params, (rs, rowNum) -> new ReminderOccurrenceRow(
                rs.getObject("task_id", UUID.class),
                rs.getObject("reminder_id", UUID.class),
                ReminderKind.valueOf(rs.getString("kind")),
                rs.getObject("fire_at", LocalDateTime.class),
                rs.getString("content")));
    }
}
