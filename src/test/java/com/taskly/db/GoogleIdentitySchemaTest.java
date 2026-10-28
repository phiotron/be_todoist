package com.taskly.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** Kiểm tra trực tiếp bằng SQL: thay đổi do V6__m5_google_identity.sql tạo. */
class GoogleIdentitySchemaTest extends IntegrationTestBase {

    @Test
    void flywayAppliedVersion6() {
        Boolean success = jdbcTemplate.queryForObject(
                "select success from flyway_schema_history where version = '6'", Boolean.class);
        assertThat(success).isTrue();
    }

    @Test
    void passwordHashAcceptsNullForGoogleOnlyAccounts() {
        UUID userId = insertUser(uniqueEmail(), null);

        String hash = jdbcTemplate.queryForObject(
                "select password_hash from users where id = ?", String.class, userId);
        assertThat(hash).isNull();
    }

    @Test
    void googleSubjectBelongsToAtMostOneUser() {
        String subject = uniqueSubject();
        insertIdentity(insertUser(uniqueEmail(), "hash-not-used"), "GOOGLE", subject);
        UUID otherUser = insertUser(uniqueEmail(), "hash-not-used");

        assertThatThrownBy(() -> insertIdentity(otherUser, "GOOGLE", subject))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_user_identities_provider_subject");
    }

    @Test
    void userHasAtMostOneIdentityPerProvider() {
        UUID userId = insertUser(uniqueEmail(), "hash-not-used");
        insertIdentity(userId, "GOOGLE", uniqueSubject());

        assertThatThrownBy(() -> insertIdentity(userId, "GOOGLE", uniqueSubject()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_user_identities_user_provider");
    }

    @Test
    void providerCheckRejectsUnknownValues() {
        UUID userId = insertUser(uniqueEmail(), "hash-not-used");

        // M13b (V18) mở thêm APPLE / FACEBOOK — giá trị lạ vẫn bị chặn.
        assertThatThrownBy(() -> insertIdentity(userId, "TWITTER", uniqueSubject()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_user_identities_provider");
    }

    @Test
    void deletingUserDeletesItsIdentities() {
        UUID userId = insertUser(uniqueEmail(), "hash-not-used");
        String subject = uniqueSubject();
        insertIdentity(userId, "GOOGLE", subject);

        jdbcTemplate.update("delete from users where id = ?", userId);

        Integer left = jdbcTemplate.queryForObject(
                "select count(*) from user_identities where subject = ?", Integer.class, subject);
        assertThat(left).isZero();
    }

    private static String uniqueSubject() {
        return "google-sub-" + UUID.randomUUID();
    }

    private UUID insertUser(String email, String passwordHash) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "insert into users (id, email, password_hash, display_name, created_at, updated_at) "
                        + "values (?, ?, ?, 'Test', now(), now())",
                id, email, passwordHash);
        return id;
    }

    private void insertIdentity(UUID userId, String provider, String subject) {
        jdbcTemplate.update(
                "insert into user_identities (id, user_id, provider, subject, email, created_at, updated_at) "
                        + "values (?, ?, ?, ?, 'g@example.com', now(), now())",
                UUID.randomUUID(), userId, provider, subject);
    }
}
