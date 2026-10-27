package com.taskly.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UserReminderSettingsTest {

    private static User newUser() {
        return new User("an@example.com", "hash-not-used", "An");
    }

    @Test
    void newUserHasReminderDefaults() {
        User user = newUser();

        assertThat(user.isRemindersEnabled()).isTrue();
        assertThat(user.getAutoReminderMinutes()).isEqualTo(30);
    }

    @Test
    void changeUpdatesBothSettings() {
        User user = newUser();

        user.changeRemindersEnabled(false);
        user.changeAutoReminderMinutes(60);

        assertThat(user.isRemindersEnabled()).isFalse();
        assertThat(user.getAutoReminderMinutes()).isEqualTo(60);
    }

    @Test
    void nullAutoReminderMinutesTurnsAutoReminderOff() {
        User user = newUser();

        user.changeAutoReminderMinutes(null);

        assertThat(user.getAutoReminderMinutes()).isNull();
        assertThat(user.isRemindersEnabled()).isTrue();
    }

    @Test
    void resetReminderSettingsRestoresDefaults() {
        User user = newUser();
        user.changeRemindersEnabled(false);
        user.changeAutoReminderMinutes(null);

        user.resetReminderSettings();

        assertThat(user.isRemindersEnabled()).isTrue();
        assertThat(user.getAutoReminderMinutes()).isEqualTo(30);
    }

    @Test
    void resetSettingsOfM3AlsoResetsReminders() {
        User user = newUser();
        user.changeRemindersEnabled(false);
        user.changeAutoReminderMinutes(1440);

        user.resetSettings();

        assertThat(user.isRemindersEnabled()).isTrue();
        assertThat(user.getAutoReminderMinutes()).isEqualTo(30);
    }
}
