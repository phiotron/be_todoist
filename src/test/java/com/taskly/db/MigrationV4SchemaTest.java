package com.taskly.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.IntegrationTestBase;
import com.taskly.security.OpaqueTokens;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** Kiểm tra trực tiếp bằng SQL: cột cài đặt, ràng buộc và bảng do V4__m3_account_settings.sql (spec bản 3) tạo. */
class MigrationV4SchemaTest extends IntegrationTestBase {

    private static final String PINNED_ID = "3f2b8c1e-7d4a-4b6e-9c1d-2a5e8f0b7c3d";

    @Test
    void flywayAppliedVersion4() {
        Boolean success = jdbcTemplate.queryForObject(
                "select success from flyway_schema_history where version = '4'", Boolean.class);
        assertThat(success).isTrue();
    }

    @Test
    void usersSettingsColumnsHaveSpecDefaults() {
        UUID userId = insertUser(uniqueEmail());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select theme, appearance, start_day, next_week, weekend_start_day, date_format, time_format, "
                        + "start_page, smart_date_recognition, swipe_left, swipe_right, nav_tabs from users where id = ?",
                userId);
        assertThat(row).containsEntry("theme", "TASKLY")
                .containsEntry("appearance", "AUTO")
                .containsEntry("start_day", 1)
                .containsEntry("next_week", 1)
                .containsEntry("weekend_start_day", 6)
                .containsEntry("date_format", "DMY")
                .containsEntry("time_format", "H24")
                .containsEntry("start_page", "TODAY")
                .containsEntry("smart_date_recognition", true)
                .containsEntry("swipe_left", "SCHEDULE")
                .containsEntry("swipe_right", "COMPLETE")
                .containsEntry("nav_tabs", "INBOX,TODAY,UPCOMING,BROWSE");
    }

    @Test
    void usersSettingsOnlyAcceptKnownValues() {
        UUID userId = insertUser(uniqueEmail());

        for (String theme : List.of("TASKLY", "MOONSTONE", "TANGERINE", "KALE", "BLUEBERRY", "LAVENDER", "RASPBERRY")) {
            update(userId, "theme", theme);
        }
        update(userId, "appearance", "DARK");
        update(userId, "start_day", 7);
        update(userId, "start_page", "project:" + PINNED_ID);
        update(userId, "nav_tabs", "TODAY,project:" + PINNED_ID + ",BROWSE");
        update(userId, "nav_tabs", "INBOX,TODAY,UPCOMING,SEARCH,BROWSE");

        rejects(userId, "theme", "SYSTEM");
        rejects(userId, "theme", "kale");
        rejects(userId, "appearance", "SYSTEM");
        rejects(userId, "start_day", 0);
        rejects(userId, "start_day", 8);
        rejects(userId, "next_week", 0);
        rejects(userId, "weekend_start_day", 8);
        rejects(userId, "date_format", "YMD");
        rejects(userId, "time_format", "24");
        rejects(userId, "start_page", "SEARCH");
        rejects(userId, "start_page", "project:not-a-uuid");
        rejects(userId, "swipe_left", "ARCHIVE");
        rejects(userId, "swipe_right", "complete");
        rejects(userId, "nav_tabs", "INBOX,BROWSE");
        rejects(userId, "nav_tabs", "");
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

    @Test
    void userTotpIsKeyedByUser_andCascadesWithUser() {
        UUID userId = insertUser(uniqueEmail());
        insertTotp(userId);

        assertThat(jdbcTemplate.queryForObject(
                "select enabled_at is null and last_used_step is null from user_totp where user_id = ?",
                Boolean.class, userId)).isTrue();
        assertThatThrownBy(() -> insertTotp(userId)).isInstanceOf(DataIntegrityViolationException.class);

        jdbcTemplate.update("delete from users where id = ?", userId);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from user_totp where user_id = ?", Integer.class, userId)).isZero();
    }

    @Test
    void userRecoveryCodesAreUniquePerUser_andCascadeWithUser() {
        UUID userId = insertUser(uniqueEmail());
        UUID otherId = insertUser(uniqueEmail());
        String hash = OpaqueTokens.sha256Hex("abcdef23");
        insertRecoveryCode(userId, hash);
        insertRecoveryCode(otherId, hash); // cùng mã ở user khác: được

        assertThatThrownBy(() -> insertRecoveryCode(userId, hash)).isInstanceOf(DataIntegrityViolationException.class);

        jdbcTemplate.update("delete from users where id = ?", userId);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from user_recovery_codes where user_id = ?", Integer.class, userId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from user_recovery_codes where user_id = ?", Integer.class, otherId)).isOne();
    }

    private void update(UUID userId, String column, Object value) {
        jdbcTemplate.update("update users set " + column + " = ? where id = ?", value, userId);
    }

    private void rejects(UUID userId, String column, Object value) {
        assertThatThrownBy(() -> update(userId, column, value))
                .as("%s = %s phải bị CHECK chặn", column, value)
                .isInstanceOf(DataIntegrityViolationException.class);
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

    private void insertTotp(UUID userId) {
        jdbcTemplate.update(
                "insert into user_totp (user_id, secret_encrypted, created_at, updated_at) "
                        + "values (?, 'ban-ma-gia', now(), now())",
                userId);
    }

    private void insertRecoveryCode(UUID userId, String codeHash) {
        jdbcTemplate.update(
                "insert into user_recovery_codes (id, user_id, code_hash, created_at, updated_at) "
                        + "values (?, ?, ?, now(), now())",
                UUID.randomUUID(), userId, codeHash);
    }
}
