package com.taskly.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Unit thuần (không Spring): biên hết hạn, dùng một lần, vô hiệu token cũ. */
class PasswordResetTokenTest {

    private static final Instant EXPIRES_AT = Instant.parse("2030-01-31T00:30:00Z");

    private static PasswordResetToken tokenExpiringAt(Instant expiresAt) {
        // user không được dùng bởi các hàm dưới nên để null
        return new PasswordResetToken(null, "0".repeat(64), expiresAt);
    }

    @Test
    void isUsable_trueOneNanoBeforeExpiry_falseExactlyAtExpiry() {
        PasswordResetToken token = tokenExpiringAt(EXPIRES_AT);

        assertThat(token.isUsable(EXPIRES_AT.minusNanos(1))).isTrue();
        assertThat(token.isUsable(EXPIRES_AT)).isFalse();
    }

    @Test
    void markUsed_makesTokenUnusableAndKeepsFirstUseTime() {
        PasswordResetToken token = tokenExpiringAt(EXPIRES_AT);
        Instant firstUse = EXPIRES_AT.minusSeconds(600);

        token.markUsed(firstUse);
        token.markUsed(firstUse.plusSeconds(60));

        assertThat(token.isUsable(firstUse)).isFalse();
        assertThat(token.getUsedAt()).isEqualTo(firstUse);
    }

    @Test
    void invalidate_activeToken_expiresItNow() {
        PasswordResetToken token = tokenExpiringAt(EXPIRES_AT);
        Instant now = EXPIRES_AT.minusSeconds(900);

        token.invalidate(now);

        assertThat(token.getExpiresAt()).isEqualTo(now);
        assertThat(token.isUsable(now)).isFalse();
        assertThat(token.getUsedAt()).isNull();
    }

    @Test
    void invalidate_usedToken_isNoop() {
        PasswordResetToken token = tokenExpiringAt(EXPIRES_AT);
        Instant now = EXPIRES_AT.minusSeconds(900);
        token.markUsed(now);

        token.invalidate(now.plusSeconds(1));

        assertThat(token.getExpiresAt()).isEqualTo(EXPIRES_AT);
    }

    @Test
    void invalidate_alreadyExpiredToken_isNoop() {
        PasswordResetToken token = tokenExpiringAt(EXPIRES_AT);

        token.invalidate(EXPIRES_AT.plusSeconds(60));

        assertThat(token.getExpiresAt()).isEqualTo(EXPIRES_AT);
    }
}
