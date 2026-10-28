package com.taskly.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Bảng api_clients (app của nhà phát triển). client_secret chỉ lưu SHA-256. */
@Repository
public class DeveloperAppRepository {

    public record AppRow(UUID id, UUID ownerId, String name, String clientId, String clientSecretHash,
                         String redirectUri, Instant createdAt) {
    }

    private static final RowMapper<AppRow> MAPPER = (rs, n) -> new AppRow(
            rs.getObject("id", UUID.class), rs.getObject("owner_id", UUID.class), rs.getString("name"),
            rs.getString("client_id"), rs.getString("client_secret_hash"), rs.getString("redirect_uri"),
            SqlTime.instant(rs, "created_at"));

    private final NamedParameterJdbcTemplate jdbc;

    public DeveloperAppRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, UUID ownerId, String name, String clientId, String secretHash, String redirectUri,
                       Instant now) {
        jdbc.update("""
                INSERT INTO api_clients (id, owner_id, name, client_id, client_secret_hash, redirect_uri, created_at,
                    updated_at)
                VALUES (:id, :owner, :name, :clientId, :hash, :redirect, :now, :now)
                """, new MapSqlParameterSource().addValue("id", id).addValue("owner", ownerId)
                .addValue("name", name).addValue("clientId", clientId).addValue("hash", secretHash)
                .addValue("redirect", redirectUri).addValue("now", SqlTime.ts(now)));
    }

    public List<AppRow> listByOwner(UUID ownerId) {
        return jdbc.query("SELECT * FROM api_clients WHERE owner_id = :owner ORDER BY created_at, id",
                new MapSqlParameterSource("owner", ownerId), MAPPER);
    }

    public Optional<AppRow> findOwned(UUID id, UUID ownerId) {
        return jdbc.query("SELECT * FROM api_clients WHERE id = :id AND owner_id = :owner",
                new MapSqlParameterSource().addValue("id", id).addValue("owner", ownerId), MAPPER)
                .stream().findFirst();
    }

    public Optional<AppRow> findByClientId(String clientId) {
        return jdbc.query("SELECT * FROM api_clients WHERE client_id = :clientId",
                new MapSqlParameterSource("clientId", clientId), MAPPER).stream().findFirst();
    }

    public void update(UUID id, String name, String redirectUri, Instant now) {
        jdbc.update("UPDATE api_clients SET name = :name, redirect_uri = :redirect, updated_at = :now WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("name", name)
                        .addValue("redirect", redirectUri).addValue("now", SqlTime.ts(now)));
    }

    public void updateSecret(UUID id, String secretHash, Instant now) {
        jdbc.update("UPDATE api_clients SET client_secret_hash = :hash, updated_at = :now WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("hash", secretHash)
                        .addValue("now", SqlTime.ts(now)));
    }

    public void delete(UUID id) {
        jdbc.update("DELETE FROM api_clients WHERE id = :id", new MapSqlParameterSource("id", id));
    }
}
