package com.taskly.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit thuần: bật, chống dùng lại bước (chỉ tiến lên), thay secret khi setup lại. */
class UserTotpTest {

    private static final Instant NOW = Instant.parse("2030-01-31T00:00:00Z");

    @Test
    void markStepUsed_onlyMovesForward() {
        UserTotp totp = new UserTotp(UUID.randomUUID(), "ban-ma-gia");

        totp.markStepUsed(100);
        totp.markStepUsed(99);
        assertThat(totp.getLastUsedStep()).isEqualTo(100L);

        totp.markStepUsed(101);
        assertThat(totp.getLastUsedStep()).isEqualTo(101L);
    }

    @Test
    void enable_thenReplaceSecret_returnsToPendingAndForgetsLastStep() {
        UserTotp totp = new UserTotp(UUID.randomUUID(), "ban-ma-1");
        assertThat(totp.isEnabled()).isFalse();
        assertThat(totp.isNew()).isTrue();

        totp.enable(NOW);
        totp.markStepUsed(5);
        assertThat(totp.isEnabled()).isTrue();
        assertThat(totp.getEnabledAt()).isEqualTo(NOW);

        totp.replaceSecret("ban-ma-2");
        assertThat(totp.getSecretEncrypted()).isEqualTo("ban-ma-2");
        assertThat(totp.isEnabled()).isFalse();
        assertThat(totp.getLastUsedStep()).isNull();
    }
}
