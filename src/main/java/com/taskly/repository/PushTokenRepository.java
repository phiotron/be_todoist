package com.taskly.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Device token FCM (bảng push_tokens, khoá duy nhất theo SHA-256 của token). */
@Repository
public class PushTokenRepository {

    /** Mỗi tài khoản giữ tối đa chừng này thiết bị (cũ nhất bị xoá). */
    public static final int MAX_PER_USER = 10;

    private final NamedParameterJdbcTemplate jdbc;

    public PushTokenRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Token đã có (của user khác) → chuyển sang user này: một thiết bị thuộc một tài khoản đăng nhập gần nhất. */
    public void upsert(UUID userId, String token, String tokenHash, String platform, Instant now) {
        jdbc.update("""
                INSERT INTO push_tokens (id, user_id, token, token_hash, platform, created_at, updated_at)
                VALUES (:id, :user, :token, :hash, :platform, :now, :now)
                ON CONFLICT (token_hash) DO UPDATE SET user_id = EXCLUDED.user_id, platform = EXCLUDED.platform,
                    updated_at = EXCLUDED.updated_at
                """, new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("user", userId)
                .addValue("token", token).addValue("hash", tokenHash).addValue("platform", platform)
                .addValue("now", SqlTime.ts(now)));
        jdbc.update("""
                DELETE FROM push_tokens WHERE user_id = :user AND id NOT IN (
                    SELECT id FROM push_tokens WHERE user_id = :user ORDER BY updated_at DESC, id LIMIT :keep)
                """, new MapSqlParameterSource().addValue("user", userId).addValue("keep", MAX_PER_USER));
    }

    public boolean deleteForUser(UUID userId, String tokenHash) {
        return jdbc.update("DELETE FROM push_tokens WHERE user_id = :user AND token_hash = :hash",
                new MapSqlParameterSource().addValue("user", userId).addValue("hash", tokenHash)) > 0;
    }

    public void deleteByHash(String tokenHash) {
        jdbc.update("DELETE FROM push_tokens WHERE token_hash = :hash", new MapSqlParameterSource("hash", tokenHash));
    }

    public List<String> tokensOf(UUID userId) {
        return jdbc.query("SELECT token FROM push_tokens WHERE user_id = :user ORDER BY updated_at DESC, id",
                new MapSqlParameterSource("user", userId), (rs, n) -> rs.getString("token"));
    }
}
