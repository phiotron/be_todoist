package com.taskly.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.IntegrationTestBase;
import com.taskly.security.OpaqueTokens;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** Kiểm tra trực tiếp bằng SQL: cột, ràng buộc và bảng do V4__m3_account_settings.sql tạo. */
class MigrationV4SchemaTest extends IntegrationTestBase {

    @Test
    void flywayAppliedVersion4() {
        Boolean success = jdbcTemplate.queryForObject(
                "select success from flyway_schema_history where version = '4'", Boolean.class);
        assertThat(success).isTrue();
    }

    @Test
    void usersThemeDefaultsToSystemAndOnlyAcceptsKnownValues() {
        UUID userId = insertUser(uniqueEmail());

        assertThat(jdbcTemplate.queryForObject("select theme from users where id = ?", String.class, userId))
                .isEqualTo("SYSTEM");
        for (String theme : List.of("LIGHT", "DARK", "SYSTEM")) {
            jdbcTemplate.update("update users set theme = ? where id = ?", theme, userId);
        }
        assertThatThrownBy(() -> jdbcTemplate.update("update users set theme = 'PURPLE' where id = ?", userId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("update users set theme = 'dark' where id = ?", userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void usersLocaleOnlyAcceptsViOrEn() {
        UUID userId = insertUser(uniqueEmail());

        jdbcTemplate.update("update users set locale = 'en' where id = ?", userId);
        jdbcTemplate.update("update users set locale = 'vi' where id = ?", userId);
        assertThatThrownBy(() -> jdbcTemplate.update("update users set locale = 'fr' where id = ?", userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void passwordResetTokensHaveUniqueHashNullableUsedAtAndCascadeWithUser() {
        UUID userId = insertUser(uniqueEmail());
        String hash = OpaqueTokens.sha256Hex(UUID.randomUUID().toString());
        insertResetToken(userId, hash);

        assertThat(jdbcTemplate.queryForObject(
                "select used_at is null from password_reset_tokens where token_hash = ?", Boolean.class, hash))
                .isTrue();
        assertThatThrownBy(() -> insertResetToken(userId, hash))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbcTemplate.update("delete from users where id = ?", userId);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from password_reset_tokens where user_id = ?", Integer.class, userId))
                .isZero();
    }

    private UUID insertUser(String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "insert into users (id, email, password_hash, display_name, created_at, updated_at) "
                        + "values (?, ?, 'hash-not-used', 'Test', now(), now())",
                id, email);
        return id;
    }

    private void insertResetToken(UUID userId, String tokenHash) {
        jdbcTemplate.update(
                "insert into password_reset_tokens (id, user_id, token_hash, expires_at, created_at, updated_at) "
                        + "values (?, ?, ?, now() + interval '30 minutes', now(), now())",
                UUID.randomUUID(), userId, tokenHash);
    }
}
