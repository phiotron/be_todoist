package com.taskly.security;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Giới hạn POST oauth/token và oauth/revoke theo IP (M13c), cùng mẫu AuthRateLimiter: cửa sổ trượt trong bộ nhớ
 * (MỘT instance), mặc định 30 lần / 60 giây. Hai endpoint dùng CHUNG một bộ đếm (cùng là nơi dò client_secret / mã).
 * IP = request.getRemoteAddr() — không tin X-Forwarded-For (xem rủi ro mục 17 của spec). Vượt → 429 + Retry-After.
 */
@Component
public class OAuthRateLimiter {

    private final RateLimiter limiter;
    private final Clock clock;

    public OAuthRateLimiter(@Value("${app.rate-limit.oauth.max-requests:30}") int maxRequests,
                            @Value("${app.rate-limit.oauth.window-seconds:60}") long windowSeconds,
                            Clock clock) {
        this.limiter = new RateLimiter(maxRequests, Duration.ofSeconds(windowSeconds));
        this.clock = clock;
    }

    public void check(HttpServletRequest request) {
        RateLimiter.Decision decision = limiter.tryAcquire("oauth:" + request.getRemoteAddr(), clock.instant());
        if (!decision.allowed()) {
            throw ApiException.tooManyRequests(ErrorMessage.TOO_MANY_REQUESTS, decision.retryAfterSeconds());
        }
    }

    /** Xoá mọi bộ đếm (test gọi trước mỗi test). */
    public void clear() {
        limiter.clear();
    }
}
