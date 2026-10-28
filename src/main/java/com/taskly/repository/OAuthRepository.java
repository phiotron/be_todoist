package com.taskly.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Mã ủy quyền + token OAuth (JDBC thuần; bí mật chỉ ở dạng SHA-256). */
@Repository
public class OAuthRepository {

    public record CodeRow(UUID id, UUID apiClientId, UUID userId, String redirectUri, String scopes,
                          String codeChallenge, Instant expiresAt, Instant usedAt) {
    }

    /** Token còn hiệu lực + client_id công khai của app, dùng để dựng Jwt cho mỗi request. */
    public record ActiveToken(UUID id, UUID userId, String clientId, String scopes, Instant lastUsedAt) {
    }

    public record AuthorizedAppRow(String clientId, String name, String scopes, Instant authorizedAt,
                                   Instant lastUsedAt) {
    }

    private static final RowMapper<CodeRow> CODE_MAPPER = (rs, n) -> new CodeRow(
            rs.getObject("id", UUID.class), rs.getObject("api_client_id", UUID.class),
            rs.getObject("user_id", UUID.class), rs.getString("redirect_uri"), rs.getString("scopes"),
            rs.getString("code_challenge"), SqlTime.instant(rs, "expires_at"), SqlTime.instant(rs, "used_at"));

    private final NamedParameterJdbcTemplate jdbc;

    public OAuthRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertCode(UUID id, UUID apiClientId, UUID userId, String codeHash, String redirectUri, String scopes,
                           String codeChallenge, Instant expiresAt, Instant now) {
        jdbc.update("""
                INSERT INTO oauth_authorization_codes (id, api_client_id, user_id, code_hash, redirect_uri, scopes,
                    code_challenge, expires_at, created_at)
                VALUES (:id, :client, :user, :hash, :redirect, :scopes, :challenge, :expires, :now)
                """, new MapSqlParameterSource().addValue("id", id).addValue("client", apiClientId)
                .addValue("user", userId).addValue("hash", codeHash).addValue("redirect", redirectUri)
                .addValue("scopes", scopes).addValue("challenge", codeChallenge)
                .addValue("expires", SqlTime.ts(expiresAt)).addValue("now", SqlTime.ts(now)));
    }

    public Optional<CodeRow> findCodeByHash(String codeHash) {
        return jdbc.query("SELECT * FROM oauth_authorization_codes WHERE code_hash = :hash",
                new MapSqlParameterSource("hash", codeHash), CODE_MAPPER).stream().findFirst();
    }

    /** Đánh dấu dùng; false = đã có người dùng trước (mã dùng lại). Nguyên tử nhờ điều kiện used_at IS NULL. */
    public boolean markCodeUsed(UUID codeId, Instant now) {
        return jdbc.update("UPDATE oauth_authorization_codes SET used_at = :now WHERE id = :id AND used_at IS NULL",
                new MapSqlParameterSource().addValue("now", SqlTime.ts(now)).addValue("id", codeId)) == 1;
    }

    public void insertToken(UUID id, UUID apiClientId, UUID userId, String tokenHash, String scopes, UUID codeId,
                            Instant now) {
        jdbc.update("""
                INSERT INTO oauth_tokens (id, api_client_id, user_id, token_hash, scopes, code_id, created_at)
                VALUES (:id, :client, :user, :hash, :scopes, :code, :now)
                """, new MapSqlParameterSource().addValue("id", id).addValue("client", apiClientId)
                .addValue("user", userId).addValue("hash", tokenHash).addValue("scopes", scopes)
                .addValue("code", codeId).addValue("now", SqlTime.ts(now)));
    }

    public Optional<ActiveToken> findActiveToken(String tokenHash) {
        return jdbc.query("""
                SELECT t.id, t.user_id, c.client_id, t.scopes, t.last_used_at
                FROM oauth_tokens t JOIN api_clients c ON c.id = t.api_client_id
                WHERE t.token_hash = :hash AND t.revoked_at IS NULL
                """, new MapSqlParameterSource("hash", tokenHash),
                (rs, n) -> new ActiveToken(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getString("client_id"), rs.getString("scopes"), SqlTime.instant(rs, "last_used_at")))
                .stream().findFirst();
    }

    /** Ghi last_used_at nhưng tối đa 1 lần / phút / token (giảm ghi DB ở mỗi request). */
    public void touch(UUID tokenId, Instant now) {
        jdbc.update("""
                UPDATE oauth_tokens SET last_used_at = :now
                WHERE id = :id AND (last_used_at IS NULL OR last_used_at < :threshold)
                """, new MapSqlParameterSource().addValue("now", SqlTime.ts(now)).addValue("id", tokenId)
                .addValue("threshold", SqlTime.ts(now.minusSeconds(60))));
    }

    /** Thu hồi token thô (RFC 7009): chỉ token của đúng app. true nếu có token bị thu hồi. */
    public boolean revokeByHash(UUID apiClientId, String tokenHash, Instant now) {
        return jdbc.update("""
                UPDATE oauth_tokens SET revoked_at = :now
                WHERE token_hash = :hash AND api_client_id = :client AND revoked_at IS NULL
                """, new MapSqlParameterSource().addValue("now", SqlTime.ts(now)).addValue("hash", tokenHash)
                .addValue("client", apiClientId)) > 0;
    }

    /** Mã bị dùng lại: thu hồi mọi token đã cấp từ mã đó (RFC 6749, mục 4.1.2). */
    public int revokeByCode(UUID codeId, Instant now) {
        return jdbc.update("UPDATE oauth_tokens SET revoked_at = :now WHERE code_id = :code AND revoked_at IS NULL",
                new MapSqlParameterSource().addValue("now", SqlTime.ts(now)).addValue("code", codeId));
    }

    /** Thu hồi mọi token của user cho app có client_id công khai. Trả số token đã thu hồi. */
    public int revokeForUserAndClient(UUID userId, String clientId, Instant now) {
        return jdbc.update("""
                UPDATE oauth_tokens SET revoked_at = :now
                WHERE user_id = :user AND revoked_at IS NULL
                  AND api_client_id = (SELECT id FROM api_clients WHERE client_id = :clientId)
                """, new MapSqlParameterSource().addValue("now", SqlTime.ts(now)).addValue("user", userId)
                .addValue("clientId", clientId));
    }

    /** Mọi token còn hiệu lực của user, mới nhất trước; service gộp theo app. */
    public List<AuthorizedAppRow> activeTokensByApp(UUID userId) {
        return jdbc.query("""
                SELECT c.client_id, c.name, t.scopes, t.created_at, t.last_used_at
                FROM oauth_tokens t JOIN api_clients c ON c.id = t.api_client_id
                WHERE t.user_id = :user AND t.revoked_at IS NULL
                ORDER BY t.created_at DESC
                """, new MapSqlParameterSource("user", userId),
                (rs, n) -> new AuthorizedAppRow(rs.getString("client_id"), rs.getString("name"),
                        rs.getString("scopes"), SqlTime.instant(rs, "created_at"), SqlTime.instant(rs, "last_used_at")));
    }
}
