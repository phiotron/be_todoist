package com.taskly.security;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

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
    private final AtomicReference<Instant> lastSweep = new AtomicReference<>(Instant.MIN);

    public RateLimiter(int maxRequests, Duration window) {
        if (maxRequests < 1 || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("maxRequests >= 1 và window > 0");
        }
        this.maxRequests = maxRequests;
        this.window = window;
    }

    public Decision tryAcquire(String key, Instant now) {
        sweepIfDue(now);
        Decision[] decision = new Decision[1];
        // compute: khoá theo key, nguyên tử với lần quét xoá key hết hạn (computeIfPresent) → không mất lượt đếm
        hits.compute(key, (k, existing) -> {
            Deque<Instant> queue = existing == null ? new ArrayDeque<>() : existing;
            Instant cutoff = now.minus(window);
            while (!queue.isEmpty() && !queue.peekFirst().isAfter(cutoff)) {
                queue.pollFirst();
            }
            if (queue.size() < maxRequests) {
                queue.addLast(now);
                decision[0] = new Decision(true, 0);
            } else {
                Duration wait = Duration.between(now, queue.peekFirst().plus(window));
                long seconds = wait.getSeconds() + (wait.getNano() > 0 ? 1 : 0);
                decision[0] = new Decision(false, Math.max(1, seconds));
            }
            return queue;
        });
        return decision[0];
    }

    /**
     * Dọn key hết hạn (mọi lượt đếm đã rời cửa sổ) — nếu không map tăng vô hạn theo số key (IP) từng gặp. Chạy tối đa
     * mỗi `window` một lần, ngay trong lần gọi tryAcquire (không cần luồng nền).
     */
    private void sweepIfDue(Instant now) {
        Instant last = lastSweep.get();
        if (now.isBefore(last.plus(window)) || !lastSweep.compareAndSet(last, now)) {
            return;
        }
        Instant cutoff = now.minus(window);
        for (String key : hits.keySet()) {
            hits.computeIfPresent(key, (k, queue) -> {
                while (!queue.isEmpty() && !queue.peekFirst().isAfter(cutoff)) {
                    queue.pollFirst();
                }
                return queue.isEmpty() ? null : queue;
            });
        }
    }

    /** Số key đang giữ (test). */
    int trackedKeys() {
        return hits.size();
    }

    /** Xoá mọi bộ đếm (test gọi trước mỗi test). */
    public void clear() {
        hits.clear();
    }
}
