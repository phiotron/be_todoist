package com.taskly.repository;

import com.taskly.entity.NotificationEvent;
import com.taskly.entity.NotificationScope;
import com.taskly.entity.NotificationType;
import com.taskly.security.ReadScope;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Bảng notifications + notification_settings (M8b, V12). Mọi đọc lọc theo dự án nằm trong :workspaceIds (phạm vi từ
 * AccessPolicy.viewScope, M8a: theo DỰ ÁN): mất quyền xem dự án → thông báo của nó không hiện, không đếm.
 */
@Repository
public class NotificationRepository {

    public record NotificationRow(UUID id, NotificationType type, UUID actorId, String actorName,
                                  Instant actorAvatarAt, UUID taskId, String taskContent, UUID projectId,
                                  String projectName, UUID commentId, String commentContent, Instant readAt,
                                  Instant createdAt) {
    }

    /** Thông báo của user + dự án của nó (task → dự án hiện tại của task; không thì project_id). */
    private static final String FROM = """
            from notifications n
            left join tasks t on t.id = n.task_id
            join projects p on p.id = coalesce(t.project_id, n.project_id)
            left join users a on a.id = n.actor_id
            left join comments c on c.id = n.comment_id
            where n.user_id = :userId and p.workspace_id in (:workspaceIds) and p.id in (:projectIds)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Xoá thông báo CHƯA ĐỌC mới nhất (type, task, người nhận); trả số hàng xoá (0 nếu đã đọc / không có). */
    public int deleteLatestUnread(UUID userId, NotificationType type, UUID taskId) {
        return jdbc.update("""
                delete from notifications where id = (select id from notifications where user_id = :userId
                  and type = :type and task_id = :taskId and read_at is null
                  order by created_at desc, id desc limit 1)
                """, new MapSqlParameterSource().addValue("userId", userId).addValue("type", type.name())
                .addValue("taskId", taskId));
    }

    public void insert(UUID id, UUID userId, NotificationType type, UUID actorId, UUID commentId, UUID taskId,
                       UUID projectId, Instant now) {
        jdbc.update("""
                insert into notifications (id, user_id, type, actor_id, comment_id, task_id, project_id, created_at)
                values (:id, :userId, :type, :actorId, :commentId, :taskId, :projectId, :now)
                """, new MapSqlParameterSource().addValue("id", id).addValue("userId", userId)
                .addValue("type", type.name()).addValue("actorId", actorId).addValue("commentId", commentId)
                .addValue("taskId", taskId).addValue("projectId", projectId).addValue("now", Timestamp.from(now)));
    }

    /**
     * Một trang, mới trước; keyset (created_at, id) giảm dần. afterCreatedAt / afterId null = trang đầu. limit = cỡ
     * trang + 1 (lookahead).
     */
    public List<NotificationRow> page(UUID userId, ReadScope scope, boolean unreadOnly,
                                      Instant afterCreatedAt, UUID afterId, int limit) {
        MapSqlParameterSource params = scope.params().addValue("userId", userId).addValue("limit", limit);
        StringBuilder sql = new StringBuilder("""
                select n.id, n.type, n.actor_id, a.display_name as actor_name, a.avatar_updated_at as actor_avatar_at,
                       n.task_id, t.content as task_content, p.id as project_id, p.name as project_name,
                       n.comment_id, c.content as comment_content, n.read_at, n.created_at
                """).append(FROM);
        if (unreadOnly) {
            sql.append(" and n.read_at is null");
        }
        if (afterId != null) {
            sql.append(" and (n.created_at, n.id) < (:afterCreatedAt, :afterId)");
            params.addValue("afterCreatedAt", OffsetDateTime.ofInstant(afterCreatedAt, ZoneOffset.UTC))
                    .addValue("afterId", afterId);
        }
        sql.append(" order by n.created_at desc, n.id desc limit :limit");
        return jdbc.query(sql.toString(), params, (rs, n) -> new NotificationRow(rs.getObject("id", UUID.class),
                NotificationType.valueOf(rs.getString("type")), rs.getObject("actor_id", UUID.class),
                rs.getString("actor_name"), CommentRepository.instant(rs, "actor_avatar_at"),
                rs.getObject("task_id", UUID.class), rs.getString("task_content"),
                rs.getObject("project_id", UUID.class), rs.getString("project_name"),
                rs.getObject("comment_id", UUID.class), rs.getString("comment_content"),
                CommentRepository.instant(rs, "read_at"), CommentRepository.instant(rs, "created_at")));
    }

    public long countUnread(UUID userId, ReadScope scope) {
        Long count = jdbc.queryForObject("select count(*) " + FROM + " and n.read_at is null",
                scope.params().addValue("userId", userId), Long.class);
        return count == null ? 0 : count;
    }

    /** Thông báo có thuộc user và còn xem được không (đánh dấu đã đọc: không thì 404). */
    public boolean isVisible(UUID id, UUID userId, ReadScope scope) {
        Long count = jdbc.queryForObject("select count(*) " + FROM + " and n.id = :id",
                scope.params().addValue("userId", userId).addValue("id", id), Long.class);
        return count != null && count > 0;
    }

    /** Đã đọc rồi thì giữ read_at cũ. */
    public void markRead(UUID id, UUID userId, Instant now) {
        jdbc.update("update notifications set read_at = :now where id = :id and user_id = :userId and read_at is null",
                new MapSqlParameterSource().addValue("id", id).addValue("userId", userId)
                        .addValue("now", Timestamp.from(now)));
    }

    public int markAllRead(UUID userId, Instant now) {
        return jdbc.update("update notifications set read_at = :now where user_id = :userId and read_at is null",
                new MapSqlParameterSource().addValue("userId", userId).addValue("now", Timestamp.from(now)));
    }

    // ---- notification_settings: chỉ lưu hàng đã đổi; vắng = bật

    public Map<NotificationEvent, Boolean> settings(UUID userId) {
        Map<NotificationEvent, Boolean> result = new EnumMap<>(NotificationEvent.class);
        jdbc.query("select scope, event, enabled from notification_settings where user_id = :userId",
                new MapSqlParameterSource("userId", userId), rs -> {
                    try {
                        NotificationEvent event = NotificationEvent.valueOf(rs.getString("event"));
                        if (event.scope() == NotificationScope.valueOf(rs.getString("scope"))) {
                            result.put(event, rs.getBoolean("enabled"));
                        }
                    } catch (IllegalArgumentException ignored) {
                        // hàng của phiên bản cũ / mới hơn — bỏ qua
                    }
                });
        return result;
    }

    public void upsertSetting(UUID userId, NotificationEvent event, boolean enabled) {
        jdbc.update("""
                insert into notification_settings (user_id, scope, event, enabled) values (:userId, :scope, :event, :enabled)
                on conflict (user_id, scope, event) do update set enabled = excluded.enabled
                """, new MapSqlParameterSource().addValue("userId", userId).addValue("scope", event.scope().name())
                .addValue("event", event.name()).addValue("enabled", enabled));
    }

    /** Người nhận có bật cờ này không (không có hàng = bật). */
    public boolean isEnabled(UUID userId, NotificationEvent event) {
        List<Boolean> rows = jdbc.queryForList("""
                select enabled from notification_settings where user_id = :userId and scope = :scope and event = :event
                """, new MapSqlParameterSource().addValue("userId", userId).addValue("scope", event.scope().name())
                .addValue("event", event.name()), Boolean.class);
        return rows.isEmpty() || Boolean.TRUE.equals(rows.get(0));
    }
}
