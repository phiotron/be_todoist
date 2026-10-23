package com.taskly.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Clock cho test: chạy theo giờ thật cộng một độ lệch dời được.
 * advance() để giả lập thời gian trôi (token hết hạn...), reset() trước mỗi test (IntegrationTestBase).
 */
public class MutableClock extends Clock {

    private volatile Duration offset = Duration.ZERO;

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.offset(Clock.system(zone), offset);
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(offset);
    }

    public void advance(Duration duration) {
        offset = offset.plus(duration);
    }

    public void reset() {
        offset = Duration.ZERO;
    }
}
