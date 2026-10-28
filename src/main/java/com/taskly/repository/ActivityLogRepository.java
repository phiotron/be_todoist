package com.taskly.repository;

import com.taskly.dto.activity.ActivityResponse;
import com.taskly.entity.ActivityEventType;
import com.taskly.entity.ActivityObjectType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Bảng activity_log (M10, V14). JDBC thuần trong transaction của thao tác gọi (cùng commit / rollback). Không nối chuỗi
 * giá trị người dùng — mọi giá trị là tham số có tên. Tên dự án lấy bằng subselect khi dự án đã nằm trong DB.
 */
@Repository
public class ActivityLogRepository {

    /** Điều kiện lọc của GET activity (null = không lọc). after = khoá con trỏ (createdAt, id) của dòng cuối trang trước. */
    public record Query(List<UUID> workspaceIds, UUID projectId, UUID taskId, ActivityObjectType objectType,
                        ActivityEventType eventType, UUID initiatorId, Instant afterCreatedAt, UUID afterId,
                        int limit) {
    }

    private static final String COLUMNS = "id, workspace_id, actor_id, object_type, object_id, event_type, project_id, "
            + "object_name, project_name, created_at";

    private final NamedParameterJdbcTemplate jdbc;

    public ActivityLogRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Một dòng với tên đối tượng cho sẵn; projectName null → đọc tên hiện tại của dự án projectId trong DB. */
    public void insert(UUID workspaceId, UUID actorId, ActivityObjectType objectType, UUID objectId,
                       ActivityEventType eventType, UUID projectId, String objectName, String projectName, Instant now) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("workspaceId", workspaceId)
                .addValue("actorId", actorId)
                .addValue("objectType", objectType.name())
                .addValue("objectId", objectId)
                .addValue("eventType", eventType.name())
                .addValue("projectId", projectId)
                .addValue("objectName", objectName)
                .addValue("projectName", projectName)
                .addValue("now", Timestamp.from(now));
        jdbc.update("insert into activity_log (" + COLUMNS + ") values (:id, :workspaceId, :actorId, :objectType, "
                + ":objectId, :eventType, :projectId, :objectName, "
                + "coalesce(:projectName, (select p.name from projects p where p.id = :projectId)), :now)", params);
    }

    /** Một dòng cho mỗi task trong taskIds (đọc tên task + dự án từ DB — các task phải đã được ghi xuống DB). */
    public void insertForTasks(Collection<UUID> taskIds, UUID actorId, ActivityEventType eventType, Instant now) {
        if (taskIds.isEmpty()) {
            return;
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("ids", taskIds)
                .addValue("actorId", actorId)
                .addValue("eventType", eventType.name())
                .addValue("now", Timestamp.from(now));
        jdbc.update("insert into activity_log (" + COLUMNS + ") "
                + "select gen_random_uuid(), t.workspace_id, :actorId, 'TASK', t.id, :eventType, t.project_id, "
                + "t.content, p.name, :now from tasks t join projects p on p.id = t.project_id where t.id in (:ids)",
                params);
    }

    /** Mới nhất trước (created_at, id giảm dần); lấy limit + 1 dòng để biết còn trang sau. */
    public List<ActivityResponse> page(Query query) {
        StringBuilder sql = new StringBuilder("select ").append(COLUMNS)
                .append(" from activity_log where workspace_id in (:workspaceIds)");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("workspaceIds", query.workspaceIds())
                .addValue("limit", query.limit() + 1);
        if (query.projectId() != null) {
            sql.append(" and project_id = :projectId");
            params.addValue("projectId", query.projectId());
        }
        if (query.taskId() != null) {
            sql.append(" and object_type = 'TASK' and object_id = :taskId");
            params.addValue("taskId", query.taskId());
        }
        if (query.objectType() != null) {
            sql.append(" and object_type = :objectType");
            params.addValue("objectType", query.objectType().name());
        }
        if (query.eventType() != null) {
            sql.append(" and event_type = :eventType");
            params.addValue("eventType", query.eventType().name());
        }
        if (query.initiatorId() != null) {
            sql.append(" and actor_id = :initiatorId");
            params.addValue("initiatorId", query.initiatorId());
        }
        if (query.afterCreatedAt() != null) {
            sql.append(" and (created_at, id) < (:afterCreatedAt, :afterId)");
            params.addValue("afterCreatedAt", Timestamp.from(query.afterCreatedAt()))
                    .addValue("afterId", query.afterId());
        }
        sql.append(" order by created_at desc, id desc limit :limit");
        return jdbc.query(sql.toString(), params, ActivityLogRepository::map);
    }

    private static ActivityResponse map(ResultSet rs, int rowNum) throws SQLException {
        return new ActivityResponse(
                rs.getObject("id", UUID.class),
                ActivityObjectType.valueOf(rs.getString("object_type")),
                rs.getObject("object_id", UUID.class),
                ActivityEventType.valueOf(rs.getString("event_type")),
                rs.getObject("project_id", UUID.class),
                rs.getString("object_name"),
                rs.getString("project_name"),
                rs.getObject("actor_id", UUID.class),
                rs.getTimestamp("created_at").toInstant());
    }
}
