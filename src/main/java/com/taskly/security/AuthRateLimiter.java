package com.taskly.security;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ProblemTexts;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Giới hạn các endpoint auth theo IP (mục 5.9): login / register mặc định 10 lần / 60 giây cho MỖI hành động,
 * refresh mặc định 30 lần / 60 giây. Ba bộ đếm tách nhau (khoá có tiền tố hành động).
 * IP = request.getRemoteAddr() — không tin X-Forwarded-For (xem rủi ro mục 17 của spec).
 */
@Component
public class AuthRateLimiter {

    private final RateLimiter limiter;
    private final RateLimiter refreshLimiter;
    private final Clock clock;

    public AuthRateLimiter(@Value("${app.rate-limit.auth.max-requests:10}") int maxRequests,
                           @Value("${app.rate-limit.auth.window-seconds:60}") long windowSeconds,
                           @Value("${app.rate-limit.refresh.max-requests:30}") int refreshMaxRequests,
                           @Value("${app.rate-limit.refresh.window-seconds:60}") long refreshWindowSeconds,
                           Clock clock) {
        this.limiter = new RateLimiter(maxRequests, Duration.ofSeconds(windowSeconds));
        this.refreshLimiter = new RateLimiter(refreshMaxRequests, Duration.ofSeconds(refreshWindowSeconds));
        this.clock = clock;
    }

    /** action = "login" | "register". Vượt giới hạn → 429 kèm Retry-After. */
    public void check(String action, HttpServletRequest request) {
        enforce(limiter, action, request);
    }

    /** auth/refresh: giới hạn riêng (mặc định 30 lần / phút / IP). Vượt → 429 kèm Retry-After. */
    public void checkRefresh(HttpServletRequest request) {
        enforce(refreshLimiter, "refresh", request);
    }

    private void enforce(RateLimiter target, String action, HttpServletRequest request) {
        RateLimiter.Decision decision = target.tryAcquire(action + ":" + request.getRemoteAddr(), clock.instant());
        if (!decision.allowed()) {
            throw ApiException.tooManyRequests(ProblemTexts.TOO_MANY_REQUESTS, decision.retryAfterSeconds());
        }
    }

    public void clear() {
        limiter.clear();
        refreshLimiter.clear();
    }
}
