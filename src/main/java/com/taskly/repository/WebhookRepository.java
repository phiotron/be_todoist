package com.taskly.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Bảng webhooks + hàng đợi webhook_deliveries (JDBC thuần). */
@Repository
public class WebhookRepository {

    public record WebhookRow(UUID id, UUID apiClientId, String url, String events, String secretEnc,
                             Instant createdAt, Instant updatedAt) {
    }

    public record DeliveryRow(UUID id, UUID webhookId, String eventName, String payload, int attempts) {
    }

    private static final RowMapper<WebhookRow> MAPPER = (rs, n) -> new WebhookRow(
            rs.getObject("id", UUID.class), rs.getObject("api_client_id", UUID.class), rs.getString("url"),
            rs.getString("events"), rs.getString("secret_enc"), SqlTime.instant(rs, "created_at"),
            SqlTime.instant(rs, "updated_at"));

    private final NamedParameterJdbcTemplate jdbc;

    public WebhookRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<WebhookRow> findByApp(UUID apiClientId) {
        return jdbc.query("SELECT * FROM webhooks WHERE api_client_id = :client",
                new MapSqlParameterSource("client", apiClientId), MAPPER).stream().findFirst();
    }

    public Optional<WebhookRow> findById(UUID id) {
        return jdbc.query("SELECT * FROM webhooks WHERE id = :id", new MapSqlParameterSource("id", id), MAPPER)
                .stream().findFirst();
    }

    public void insert(UUID id, UUID apiClientId, String url, String events, String secretEnc, Instant now) {
        jdbc.update("""
                INSERT INTO webhooks (id, api_client_id, url, events, secret_enc, created_at, updated_at)
                VALUES (:id, :client, :url, :events, :secret, :now, :now)
                """, new MapSqlParameterSource().addValue("id", id).addValue("client", apiClientId)
                .addValue("url", url).addValue("events", events).addValue("secret", secretEnc)
                .addValue("now", SqlTime.ts(now)));
    }

    public void update(UUID id, String url, String events, Instant now) {
        jdbc.update("UPDATE webhooks SET url = :url, events = :events, updated_at = :now WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("url", url).addValue("events", events)
                        .addValue("now", SqlTime.ts(now)));
    }

    public void updateSecret(UUID id, String secretEnc, Instant now) {
        jdbc.update("UPDATE webhooks SET secret_enc = :secret, updated_at = :now WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("secret", secretEnc)
                        .addValue("now", SqlTime.ts(now)));
    }

    public boolean deleteByApp(UUID apiClientId) {
        return jdbc.update("DELETE FROM webhooks WHERE api_client_id = :client",
                new MapSqlParameterSource("client", apiClientId)) > 0;
    }

    /** Webhook nhận sự kiện: app đang có token còn hiệu lực của user, token có scope data:* và webhook đăng ký sự kiện. */
    public List<UUID> findTargets(UUID userId, String eventName) {
        return jdbc.query("""
                SELECT w.id FROM webhooks w
                WHERE position(',' || :event || ',' IN ',' || w.events || ',') > 0
                  AND EXISTS (SELECT 1 FROM oauth_tokens t
                              WHERE t.api_client_id = w.api_client_id AND t.user_id = :user
                                AND t.revoked_at IS NULL AND t.scopes LIKE '%data:%')
                """, new MapSqlParameterSource().addValue("event", eventName).addValue("user", userId),
                (rs, n) -> rs.getObject("id", UUID.class));
    }

    public void insertDelivery(UUID id, UUID webhookId, UUID userId, String eventName, String payload, Instant now) {
        jdbc.update("""
                INSERT INTO webhook_deliveries (id, webhook_id, user_id, event_name, payload, next_attempt_at,
                    created_at, updated_at)
                VALUES (:id, :webhook, :user, :event, :payload, :now, :now, :now)
                """, new MapSqlParameterSource().addValue("id", id).addValue("webhook", webhookId)
                .addValue("user", userId).addValue("event", eventName).addValue("payload", payload)
                .addValue("now", SqlTime.ts(now)));
    }

    /** Lấy tối đa limit hàng tới hạn và "thuê" chúng tới lease (không ai khác lấy; chết giữa chừng thì hết thuê là thử lại). */
    public List<DeliveryRow> claimDue(Instant now, Instant lease, int limit) {
        return jdbc.query("""
                WITH due AS (
                    SELECT id FROM webhook_deliveries
                    WHERE status = 'PENDING' AND next_attempt_at <= :now
                    ORDER BY next_attempt_at, id LIMIT :limit FOR UPDATE SKIP LOCKED)
                UPDATE webhook_deliveries d SET next_attempt_at = :lease, updated_at = :now
                FROM due WHERE d.id = due.id
                RETURNING d.id, d.webhook_id, d.event_name, d.payload, d.attempts
                """, new MapSqlParameterSource().addValue("now", SqlTime.ts(now)).addValue("lease", SqlTime.ts(lease))
                .addValue("limit", limit),
                (rs, n) -> new DeliveryRow(rs.getObject("id", UUID.class), rs.getObject("webhook_id", UUID.class),
                        rs.getString("event_name"), rs.getString("payload"), rs.getInt("attempts")));
    }

    public void recordAttempt(UUID id, int attempts, String status, Instant nextAttemptAt, Integer statusCode,
                              Instant now) {
        jdbc.update("""
                UPDATE webhook_deliveries SET attempts = :attempts, status = :status, next_attempt_at = :next,
                    last_status_code = :code, updated_at = :now
                WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", id).addValue("attempts", attempts)
                .addValue("status", status).addValue("next", SqlTime.ts(nextAttemptAt)).addValue("code", statusCode)
                .addValue("now", SqlTime.ts(now)));
    }
}
