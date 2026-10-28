package com.taskly.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Hàng đợi push / email (notification_deliveries). UNIQUE (user, channel, dedupe_key) chống gửi trùng. */
@Repository
public class NotificationDeliveryRepository {

    public record Row(UUID id, UUID userId, String channel, String kind, String title, String body, String data,
                      int attempts) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationDeliveryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** true nếu thêm mới; false nếu khoá chống gửi trùng đã có (đã xếp hoặc đã gửi). */
    public boolean insertIfAbsent(UUID userId, String channel, String kind, String dedupeKey, String title, String body,
                                  String data, Instant now) {
        return jdbc.update("""
                INSERT INTO notification_deliveries (id, user_id, channel, kind, dedupe_key, title, body, data,
                    next_attempt_at, created_at, updated_at)
                VALUES (:id, :user, :channel, :kind, :key, :title, :body, :data, :now, :now, :now)
                ON CONFLICT (user_id, channel, dedupe_key) DO NOTHING
                """, new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("user", userId)
                .addValue("channel", channel).addValue("kind", kind).addValue("key", dedupeKey)
                .addValue("title", title).addValue("body", body).addValue("data", data)
                .addValue("now", SqlTime.ts(now))) == 1;
    }

    public List<Row> claimDue(Instant now, Instant lease, int limit) {
        return jdbc.query("""
                WITH due AS (
                    SELECT id FROM notification_deliveries
                    WHERE status = 'PENDING' AND next_attempt_at <= :now
                    ORDER BY next_attempt_at, id LIMIT :limit FOR UPDATE SKIP LOCKED)
                UPDATE notification_deliveries d SET next_attempt_at = :lease, updated_at = :now
                FROM due WHERE d.id = due.id
                RETURNING d.id, d.user_id, d.channel, d.kind, d.title, d.body, d.data, d.attempts
                """, new MapSqlParameterSource().addValue("now", SqlTime.ts(now)).addValue("lease", SqlTime.ts(lease))
                .addValue("limit", limit),
                (rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getString("channel"), rs.getString("kind"), rs.getString("title"), rs.getString("body"),
                        rs.getString("data"), rs.getInt("attempts")));
    }

    public void recordAttempt(UUID id, int attempts, String status, Instant nextAttemptAt, Instant now) {
        jdbc.update("""
                UPDATE notification_deliveries SET attempts = :attempts, status = :status, next_attempt_at = :next,
                    updated_at = :now WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", id).addValue("attempts", attempts)
                .addValue("status", status).addValue("next", SqlTime.ts(nextAttemptAt)).addValue("now", SqlTime.ts(now)));
    }
}
