package com.taskly.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.IntegrationTestBase;
import com.taskly.repository.PasswordResetTokenRepository;
import com.taskly.repository.UserRepository;
import com.taskly.security.OpaqueTokens;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** Mapping cột cài đặt (spec bản 3) của users, bảng password_reset_tokens, và "gán giá trị giống hệt không đổi updated_at". */
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
    void newUserHasSpecDefaults_andChangesArePersisted() {
        User user = userRepository.saveAndFlush(new User(uniqueEmail(), "hash-not-used", "Test"));
        assertThat(user.getTheme()).isEqualTo(Theme.TASKLY);
        assertThat(user.getAppearance()).isEqualTo(Appearance.AUTO);
        assertThat(user.getStartDay()).isEqualTo(1);
        assertThat(user.getNextWeek()).isEqualTo(1);
        assertThat(user.getWeekendStartDay()).isEqualTo(6);
        assertThat(user.getDateFormat()).isEqualTo(DateFormat.DMY);
        assertThat(user.getTimeFormat()).isEqualTo(TimeFormat.H24);
        assertThat(user.getStartPage()).isEqualTo("TODAY");
        assertThat(user.isSmartDateRecognition()).isTrue();
        assertThat(user.getSwipeLeft()).isEqualTo(SwipeAction.SCHEDULE);
        assertThat(user.getSwipeRight()).isEqualTo(SwipeAction.COMPLETE);
        assertThat(user.getNavTabs()).containsExactly("INBOX", "TODAY", "UPCOMING", "BROWSE");

        transactionTemplate.executeWithoutResult(status -> {
            User managed = userRepository.findById(user.getId()).orElseThrow();
            managed.changeTheme(Theme.KALE);
            managed.changeAppearance(Appearance.DARK);
            managed.changeLocale("en");
            managed.changeTimeZone("Europe/London");
            managed.changeDisplayName("Tên mới");
            managed.changePasswordHash("new-hash-not-used");
            managed.changeStartDay(7);
            managed.changeNextWeek(5);
            managed.changeWeekendStartDay(7);
            managed.changeDateFormat(DateFormat.MDY);
            managed.changeTimeFormat(TimeFormat.H12);
            managed.changeStartPage("UPCOMING");
            managed.changeSmartDateRecognition(false);
            managed.changeSwipeLeft(SwipeAction.DELETE);
            managed.changeSwipeRight(SwipeAction.NONE);
            managed.changeNavTabs(List.of("TODAY", "SEARCH", "BROWSE"));
        });

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select theme, appearance, locale, time_zone, display_name, password_hash, start_day, next_week, "
                        + "weekend_start_day, date_format, time_format, start_page, smart_date_recognition, "
                        + "swipe_left, swipe_right, nav_tabs from users where id = ?",
                user.getId());
        assertThat(row).containsEntry("theme", "KALE")
                .containsEntry("appearance", "DARK")
                .containsEntry("locale", "en")
                .containsEntry("time_zone", "Europe/London")
                .containsEntry("display_name", "Tên mới")
                .containsEntry("password_hash", "new-hash-not-used")
                .containsEntry("start_day", 7)
                .containsEntry("next_week", 5)
                .containsEntry("weekend_start_day", 7)
                .containsEntry("date_format", "MDY")
                .containsEntry("time_format", "H12")
                .containsEntry("start_page", "UPCOMING")
                .containsEntry("smart_date_recognition", false)
                .containsEntry("swipe_left", "DELETE")
                .containsEntry("swipe_right", "NONE")
                .containsEntry("nav_tabs", "TODAY,SEARCH,BROWSE");
    }

    @Test
    void resetSettings_restoresEveryDefaultButKeepsTimeZone() {
        User user = new User(uniqueEmail(), "hash-not-used", "Test");
        user.changeTheme(Theme.RASPBERRY);
        user.changeAppearance(Appearance.LIGHT);
        user.changeLocale("en");
        user.changeTimeZone("Europe/London");
        user.changeStartDay(3);
        user.changeNextWeek(4);
        user.changeWeekendStartDay(5);
        user.changeDateFormat(DateFormat.MDY);
        user.changeTimeFormat(TimeFormat.H12);
        user.changeStartPage("INBOX");
        user.changeSmartDateRecognition(false);
        user.changeSwipeLeft(SwipeAction.SELECT);
        user.changeSwipeRight(SwipeAction.REMINDERS);
        user.changeNavTabs(List.of("SEARCH", "TODAY", "BROWSE"));

        user.resetSettings();

        assertThat(user.getTheme()).isEqualTo(Theme.TASKLY);
        assertThat(user.getAppearance()).isEqualTo(Appearance.AUTO);
        assertThat(user.getLocale()).isEqualTo("vi");
        assertThat(user.getStartDay()).isEqualTo(1);
        assertThat(user.getNextWeek()).isEqualTo(1);
        assertThat(user.getWeekendStartDay()).isEqualTo(6);
        assertThat(user.getDateFormat()).isEqualTo(DateFormat.DMY);
        assertThat(user.getTimeFormat()).isEqualTo(TimeFormat.H24);
        assertThat(user.getStartPage()).isEqualTo("TODAY");
        assertThat(user.isSmartDateRecognition()).isTrue();
        assertThat(user.getSwipeLeft()).isEqualTo(SwipeAction.SCHEDULE);
        assertThat(user.getSwipeRight()).isEqualTo(SwipeAction.COMPLETE);
        assertThat(user.getNavTabs()).containsExactly("INBOX", "TODAY", "UPCOMING", "BROWSE");
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
            managed.changeTheme(User.DEFAULT_THEME);
            managed.changeAppearance(User.DEFAULT_APPEARANCE);
            managed.changeLocale(User.DEFAULT_LOCALE);
            managed.changeTimeZone(User.DEFAULT_TIME_ZONE);
            managed.changeStartDay(User.DEFAULT_START_DAY);
            managed.changeStartPage(User.DEFAULT_START_PAGE);
            managed.changeSmartDateRecognition(true);
            managed.changeNavTabs(List.of("INBOX", "TODAY", "UPCOMING", "BROWSE"));
        });
        assertThat(updatedAt(user.getId())).isEqualTo(before);

        transactionTemplate.executeWithoutResult(status ->
                userRepository.findById(user.getId()).orElseThrow().changeAppearance(Appearance.DARK));
        assertThat(updatedAt(user.getId())).isAfter(before);
    }

    @Test
    void navTabs_areStoredCommaSeparated_andReadBackAsList() {
        User user = userRepository.saveAndFlush(new User(uniqueEmail(), "hash-not-used", "Test"));
        String pinned = "project:3f2b8c1e-7d4a-4b6e-9c1d-2a5e8f0b7c3d";

        transactionTemplate.executeWithoutResult(status -> userRepository.findById(user.getId()).orElseThrow()
                .changeNavTabs(List.of("TODAY", pinned, "FILTERS_LABELS", "BROWSE")));

        assertThat(jdbcTemplate.queryForObject("select nav_tabs from users where id = ?", String.class, user.getId()))
                .isEqualTo("TODAY," + pinned + ",FILTERS_LABELS,BROWSE");
        List<String> readBack = transactionTemplate.execute(status ->
                userRepository.findById(user.getId()).orElseThrow().getNavTabs());
        assertThat(readBack).containsExactly("TODAY", pinned, "FILTERS_LABELS", "BROWSE");
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
