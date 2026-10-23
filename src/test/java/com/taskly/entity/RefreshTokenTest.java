package com.taskly.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Unit test thuần (không Spring) cho biên hết hạn và thu hồi của RefreshToken.isActive. */
class RefreshTokenTest {

    private static final Instant EXPIRES_AT = Instant.parse("2030-01-31T00:00:00Z");

    private static RefreshToken tokenExpiringAt(Instant expiresAt) {
        // user không được dùng bởi isActive/revoke nên để null
        return new RefreshToken(null, "0".repeat(64), expiresAt);
    }

    @Test
    void isActive_trueOneNanoBeforeExpiry() {
        assertThat(tokenExpiringAt(EXPIRES_AT).isActive(EXPIRES_AT.minusNanos(1))).isTrue();
    }

    @Test
    void isActive_falseExactlyAtExpiry() {
        assertThat(tokenExpiringAt(EXPIRES_AT).isActive(EXPIRES_AT)).isFalse();
    }

    @Test
    void isActive_falseAfterRevokeEvenBeforeExpiry() {
        RefreshToken token = tokenExpiringAt(EXPIRES_AT);
        Instant now = EXPIRES_AT.minusSeconds(3600);
        assertThat(token.isActive(now)).isTrue();

        token.revoke(now);

        assertThat(token.isActive(now)).isFalse();
        assertThat(token.getRevokedAt()).isEqualTo(now);
    }

    @Test
    void revoke_isIdempotentAndKeepsFirstTimestamp() {
        RefreshToken token = tokenExpiringAt(EXPIRES_AT);
        Instant first = EXPIRES_AT.minusSeconds(100);

        token.revoke(first);
        token.revoke(first.plusSeconds(50));

        assertThat(token.getRevokedAt()).isEqualTo(first);
    }
}
