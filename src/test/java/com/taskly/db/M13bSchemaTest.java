package com.taskly.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** Kiểm trực tiếp bằng SQL thay đổi của V18__m13b_identity_calendar.sql. */
class M13bSchemaTest extends IntegrationTestBase {

    @Test
    void flywayAppliedVersion18() {
        assertThat(jdbcTemplate.queryForObject("select success from flyway_schema_history where version = '18'",
                Boolean.class)).isTrue();
    }

    @Test
    void identitiesAcceptAppleAndFacebookAndANullEmail() {
        UUID userId = registerUserId();
        insertIdentity(userId, "APPLE", "a-" + UUID.randomUUID(), "relay@privaterelay.appleid.com");
        insertIdentity(userId, "FACEBOOK", "f-" + UUID.randomUUID(), null);

        assertThat(jdbcTemplate.queryForObject("select count(*) from user_identities where user_id = ?",
                Integer.class, userId)).isEqualTo(2);
    }

    @Test
    void oneCalendarConnectionPerUserAndProviderChecked() {
        UUID userId = registerUserId();
        insertConnection(userId, "GOOGLE");

        assertThatThrownBy(() -> insertConnection(userId, "OUTLOOK"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_calendar_connections_user");
        assertThatThrownBy(() -> insertConnection(registerUserId(), "ICLOUD"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_calendar_connections_provider");
    }

    @Test
    void oneAccountFeedPerUserAndCascadeOnUserDelete() {
        UUID userId = registerUserId();
        insertFeed(userId, null);

        assertThatThrownBy(() -> insertFeed(userId, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_calendar_feeds_account");
        UUID connectionId = insertConnection(userId, "GOOGLE");
        jdbcTemplate.update("insert into calendar_task_links (id, connection_id, task_id, event_id, fingerprint, "
                + "task_version, created_at, updated_at) values (?, ?, ?, 'e', 'f', 0, now(), now())",
                UUID.randomUUID(), connectionId, UUID.randomUUID());

        jdbcTemplate.update("delete from users where id = ?", userId);

        assertThat(jdbcTemplate.queryForObject("select count(*) from calendar_feeds where user_id = ?", Integer.class,
                userId)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from calendar_task_links where connection_id = ?",
                Integer.class, connectionId)).isZero();
    }

    private UUID registerUserId() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("insert into users (id, email, password_hash, display_name, created_at, updated_at) "
                + "values (?, ?, 'h', 'T', now(), now())", id, uniqueEmail());
        return id;
    }

    private void insertIdentity(UUID userId, String provider, String subject, String email) {
        jdbcTemplate.update("insert into user_identities (id, user_id, provider, subject, email, created_at, updated_at) "
                + "values (?, ?, ?, ?, ?, now(), now())", UUID.randomUUID(), userId, provider, subject, email);
    }

    private UUID insertConnection(UUID userId, String provider) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("insert into calendar_connections (id, user_id, provider, access_token_enc, created_at, "
                + "updated_at) values (?, ?, ?, 'enc', now(), now())", id, userId, provider);
        return id;
    }

    private void insertFeed(UUID userId, UUID projectId) {
        jdbcTemplate.update("insert into calendar_feeds (id, user_id, project_id, token_hash, token_enc, created_at, "
                + "updated_at) values (?, ?, ?, ?, 'enc', now(), now())", UUID.randomUUID(), userId, projectId,
                UUID.randomUUID().toString());
    }
}
