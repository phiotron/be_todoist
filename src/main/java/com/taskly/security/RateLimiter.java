package com.taskly.security;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Giới hạn tần suất cửa sổ trượt, bộ đếm trong bộ nhớ (mục 5.9) — chỉ đúng với MỘT instance (demo).
 * Thuần (thời điểm truyền vào), thread-safe. Mỗi key giữ thời điểm các lần gọi trong cửa sổ.
 */
public class RateLimiter {

    public record Decision(boolean allowed, long retryAfterSeconds) {
    }

    private final int maxRequests;
    private final Duration window;
    private final ConcurrentHashMap<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    public RateLimiter(int maxRequests, Duration window) {
        if (maxRequests < 1 || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("maxRequests >= 1 và window > 0");
        }
        this.maxRequests = maxRequests;
        this.window = window;
    }

    public Decision tryAcquire(String key, Instant now) {
        Deque<Instant> queue = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (queue) {
            Instant cutoff = now.minus(window);
            while (!queue.isEmpty() && !queue.peekFirst().isAfter(cutoff)) {
                queue.pollFirst();
            }
            if (queue.size() < maxRequests) {
                queue.addLast(now);
                return new Decision(true, 0);
            }
            Duration wait = Duration.between(now, queue.peekFirst().plus(window));
            long seconds = wait.getSeconds() + (wait.getNano() > 0 ? 1 : 0);
            return new Decision(false, Math.max(1, seconds));
        }
    }

    /** Xoá mọi bộ đếm (test gọi trước mỗi test). */
    public void clear() {
        hits.clear();
    }
}
