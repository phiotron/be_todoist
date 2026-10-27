package com.taskly.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ClockConfigTest {

    @Test
    void clockIsSystemClockInUtc() {
        Clock clock = new ClockConfig().clock();

        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(Duration.between(clock.instant(), Instant.now()).abs()).isLessThan(Duration.ofSeconds(5));
    }


    @Test
    void clockHasMillisecondPrecision_soJsonAndPostgresAgree() {
        assertThat(new ClockConfig().clock().instant().getNano() % 1_000_000).isZero();
    }
}
