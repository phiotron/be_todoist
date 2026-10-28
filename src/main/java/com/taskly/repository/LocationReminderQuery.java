package com.taskly.repository;

import com.taskly.dto.location.LocationReminderResponse;
import com.taskly.entity.LocationTrigger;
import com.taskly.security.ReadScope;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Nhắc theo vị trí app cần đăng ký geofence (M12) — SQL tay chỉ đọc, cùng phạm vi với ReminderOccurrenceQuery: task chưa
 * xong trong workspace đọc được, dự án / section chưa lưu trữ, nhắc của chính user. Theo thời gian tạo, tối đa :limit
 * (Android giới hạn 100 geofence / app).
 */
@Repository
public class LocationReminderQuery {

    private static final String SQL = """
            SELECT r.id AS reminder_id, t.id AS task_id, t.content, l.id AS location_id, l.name,
                   l.latitude, l.longitude, l.radius_m, r.location_trigger
            FROM reminders r
            JOIN tasks t ON t.id = r.task_id
            JOIN projects p ON p.id = t.project_id
            LEFT JOIN sections s ON s.id = t.section_id
            JOIN locations l ON l.id = r.location_id
            WHERE r.user_id = :userId
              AND r.type = 'LOCATION'
              AND t.workspace_id IN (:workspaceIds)
              AND t.project_id IN (:projectIds)
              AND t.completed_at IS NULL
              AND p.archived_at IS NULL
              AND (s.id IS NULL OR s.archived_at IS NULL)
            ORDER BY r.created_at, r.id
            LIMIT :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public LocationReminderQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** M8a: phạm vi = dự án đã tham gia (ReadScope); nhắc vị trí chỉ của chính userId. */
    public List<LocationReminderResponse> find(UUID userId, ReadScope scope, int limit) {
        if (scope.isEmpty()) {
            return List.of(); // "IN ()" không hợp lệ trong SQL
        }
        MapSqlParameterSource params = scope.params()
                .addValue("userId", userId)
                .addValue("limit", limit);
        return jdbc.query(SQL, params, (rs, rowNum) -> new LocationReminderResponse(
                rs.getObject("reminder_id", UUID.class),
                rs.getObject("task_id", UUID.class),
                rs.getString("content"),
                rs.getObject("location_id", UUID.class),
                rs.getString("name"),
                rs.getDouble("latitude"),
                rs.getDouble("longitude"),
                rs.getInt("radius_m"),
                LocationTrigger.valueOf(rs.getString("location_trigger"))));
    }
}
