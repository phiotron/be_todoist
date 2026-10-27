package com.taskly.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private static final Instant T0 = Instant.parse("2026-10-23T10:00:00Z");

    @Test
    void allowsUpToLimitThenDeniesWithRetryAfter() {
        RateLimiter limiter = new RateLimiter(3, Duration.ofSeconds(60));

        assertThat(limiter.tryAcquire("k", T0).allowed()).isTrue();
        assertThat(limiter.tryAcquire("k", T0.plusSeconds(10)).allowed()).isTrue();
        assertThat(limiter.tryAcquire("k", T0.plusSeconds(20)).allowed()).isTrue();

        RateLimiter.Decision denied = limiter.tryAcquire("k", T0.plusSeconds(30));
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isEqualTo(30); // lần đầu (T0) rời cửa sổ lúc T0+60
    }

    @Test
    void slidingWindow_freesSlotsAsOldHitsExpire() {
        RateLimiter limiter = new RateLimiter(2, Duration.ofSeconds(60));
        limiter.tryAcquire("k", T0);
        limiter.tryAcquire("k", T0.plusSeconds(30));
        assertThat(limiter.tryAcquire("k", T0.plusSeconds(59)).allowed()).isFalse();

        assertThat(limiter.tryAcquire("k", T0.plusSeconds(60)).allowed()).isTrue();
    }

    @Test
    void keysAreIndependent_andClearResets() {
        RateLimiter limiter = new RateLimiter(1, Duration.ofSeconds(60));
        assertThat(limiter.tryAcquire("a", T0).allowed()).isTrue();
        assertThat(limiter.tryAcquire("b", T0).allowed()).isTrue();
        assertThat(limiter.tryAcquire("a", T0).allowed()).isFalse();

        limiter.clear();
        assertThat(limiter.tryAcquire("a", T0).allowed()).isTrue();
    }

    @Test
    void retryAfterIsAtLeastOneSecond() {
        RateLimiter limiter = new RateLimiter(1, Duration.ofSeconds(60));
        limiter.tryAcquire("k", T0);
        assertThat(limiter.tryAcquire("k", T0.plusMillis(59_900)).retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void expiredKeysAreSweptSoTheMapDoesNotGrowForever() {
        RateLimiter limiter = new RateLimiter(2, Duration.ofSeconds(60));
        for (int i = 0; i < 100; i++) {
            limiter.tryAcquire("ip-" + i, T0);
        }
        assertThat(limiter.trackedKeys()).isEqualTo(100);

        limiter.tryAcquire("ip-moi", T0.plusSeconds(61)); // mọi key cũ đã rời cửa sổ

        assertThat(limiter.trackedKeys()).isEqualTo(1);
        assertThat(limiter.tryAcquire("ip-moi", T0.plusSeconds(62)).allowed()).isTrue(); // vẫn đếm đúng
        assertThat(limiter.tryAcquire("ip-moi", T0.plusSeconds(63)).allowed()).isFalse();
    }

    @Test
    void sweepKeepsKeysWithRecentHits() {
        RateLimiter limiter = new RateLimiter(1, Duration.ofSeconds(60));
        limiter.tryAcquire("cu", T0);
        limiter.tryAcquire("gan-day", T0.plusSeconds(50));

        limiter.tryAcquire("khac", T0.plusSeconds(61));

        assertThat(limiter.trackedKeys()).isEqualTo(2); // "cu" bị quét; "gan-day" + "khac" còn
        assertThat(limiter.tryAcquire("gan-day", T0.plusSeconds(62)).allowed()).isFalse();
    }
}
