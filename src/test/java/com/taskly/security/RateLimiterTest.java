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
}
