package com.taskly.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.IntegrationTestBase;
import com.taskly.repository.PasswordResetTokenRepository;
import com.taskly.repository.UserRepository;
import com.taskly.security.OpaqueTokens;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** Mapping cột M3 của users, bảng password_reset_tokens, và "gán giá trị giống hệt không đổi updated_at". */
class AccountEntityMappingTest extends IntegrationTestBase {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Instant updatedAt(UUID userId) {
        return jdbcTemplate.queryForObject("select updated_at from users where id = ?", Timestamp.class, userId)
                .toInstant();
    }

    @Test
    void newUserHasSystemTheme_andChangesArePersisted() {
        User user = userRepository.saveAndFlush(new User(uniqueEmail(), "hash-not-used", "Test"));
        assertThat(user.getTheme()).isEqualTo(Theme.SYSTEM);

        transactionTemplate.executeWithoutResult(status -> {
            User managed = userRepository.findById(user.getId()).orElseThrow();
            managed.changeTheme(Theme.DARK);
            managed.changeLocale("en");
            managed.changeTimeZone("Europe/London");
            managed.changeDisplayName("Tên mới");
            managed.changePasswordHash("new-hash-not-used");
        });

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select theme, locale, time_zone, display_name, password_hash from users where id = ?",
                user.getId());
        assertThat(row).containsEntry("theme", "DARK")
                .containsEntry("locale", "en")
                .containsEntry("time_zone", "Europe/London")
                .containsEntry("display_name", "Tên mới")
                .containsEntry("password_hash", "new-hash-not-used");
    }

    @Test
    void resetSettings_restoresThemeAndLocaleButKeepsTimeZone() {
        User user = new User(uniqueEmail(), "hash-not-used", "Test");
        user.changeTheme(Theme.LIGHT);
        user.changeLocale("en");
        user.changeTimeZone("Europe/London");

        user.resetSettings();

        assertThat(user.getTheme()).isEqualTo(Theme.SYSTEM);
        assertThat(user.getLocale()).isEqualTo("vi");
        assertThat(user.getTimeZone()).isEqualTo("Europe/London");
    }

    @Test
    void assigningSameValues_doesNotTouchUpdatedAt() {
        User user = userRepository.saveAndFlush(new User(uniqueEmail(), "hash-not-used", "Test"));
        Instant before = updatedAt(user.getId());
        clock.advance(Duration.ofMinutes(1));

        transactionTemplate.executeWithoutResult(status -> {
            User managed = userRepository.findById(user.getId()).orElseThrow();
            managed.changeDisplayName("Test");
            managed.changeTheme(Theme.SYSTEM);
            managed.changeLocale(User.DEFAULT_LOCALE);
            managed.changeTimeZone(User.DEFAULT_TIME_ZONE);
        });
        assertThat(updatedAt(user.getId())).isEqualTo(before);

        transactionTemplate.executeWithoutResult(status ->
                userRepository.findById(user.getId()).orElseThrow().changeTheme(Theme.DARK));
        assertThat(updatedAt(user.getId())).isAfter(before);
    }

    @Test
    void savesPasswordResetToken_andFindsItByHashAndAsActive() {
        User user = userRepository.saveAndFlush(new User(uniqueEmail(), "hash-not-used", "Test"));
        String hash = OpaqueTokens.sha256Hex(UUID.randomUUID().toString());
        Instant expiresAt = clock.instant().plus(Duration.ofMinutes(30));
        passwordResetTokenRepository.saveAndFlush(new PasswordResetToken(user, hash, expiresAt));

        // findByTokenHash khoá dòng (FOR UPDATE) → phải chạy trong transaction ghi
        PasswordResetToken found = transactionTemplate.execute(status ->
                passwordResetTokenRepository.findByTokenHash(hash).orElseThrow());

        assertThat(found.getId()).isNotNull();
        assertThat(found.getUsedAt()).isNull();
        assertThat(found.isUsable(clock.instant())).isTrue();
        assertThat(passwordResetTokenRepository.findAllByUserIdAndUsedAtIsNullAndExpiresAtAfter(
                user.getId(), clock.instant())).hasSize(1);
        assertThat(passwordResetTokenRepository.findAllByUserIdAndUsedAtIsNullAndExpiresAtAfter(
                user.getId(), expiresAt)).isEmpty();
    }
}
