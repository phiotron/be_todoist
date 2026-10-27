package com.taskly.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Clock cho test: mặc định chạy theo giờ thật cộng độ lệch dời được (advance).
 * freezeAt(t) đóng băng ở t (cho test ranh giới nửa đêm); advance vẫn cộng thêm. reset() trước mỗi test.
 */
public class MutableClock extends Clock {

    private volatile Duration offset = Duration.ZERO;
    private volatile Instant frozenAt;

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    /** Clock "sống" theo cùng trạng thái (advance/freeze) nhưng múi giờ khác. */
    @Override
    public Clock withZone(ZoneId zone) {
        MutableClock self = this;
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return zone;
            }

            @Override
            public Clock withZone(ZoneId other) {
                return self.withZone(other);
            }

            @Override
            public Instant instant() {
                return self.instant();
            }
        };
    }

    /** Làm tròn mili giây như ClockConfig (khớp Postgres). */
    @Override
    public Instant instant() {
        Instant base = frozenAt != null ? frozenAt : Instant.now();
        return base.plus(offset).truncatedTo(ChronoUnit.MILLIS);
    }

    public void advance(Duration duration) {
        offset = offset.plus(duration);
    }

    public void freezeAt(Instant instant) {
        frozenAt = instant;
        offset = Duration.ZERO;
    }

    public void reset() {
        offset = Duration.ZERO;
        frozenAt = null;
    }
}
