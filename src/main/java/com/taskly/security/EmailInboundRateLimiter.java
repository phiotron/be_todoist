package com.taskly.security;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Giới hạn tần suất webhook email inbound (M13a), cùng mẫu AuthRateLimiter (bộ đếm trong bộ nhớ, MỘT instance):
 * - theo IP (mặc định 120 request / 60 giây) — chạy TRƯỚC khi kiểm chữ ký, chặn dò bí mật;
 * - theo địa chỉ nhận thư (mặc định 50 thư / 3600 giây) — chặn một địa chỉ bị lộ làm ngập dự án.
 * IP = request.getRemoteAddr() (không tin X-Forwarded-For, như mục 17 của spec).
 */
@Component
public class EmailInboundRateLimiter {

    private final RateLimiter perIp;
    private final RateLimiter perAddress;
    private final Clock clock;

    public EmailInboundRateLimiter(
            @Value("${app.rate-limit.email-inbound.per-ip.max-requests:120}") int ipMax,
            @Value("${app.rate-limit.email-inbound.per-ip.window-seconds:60}") long ipWindowSeconds,
            @Value("${app.rate-limit.email-inbound.per-address.max-requests:50}") int addressMax,
            @Value("${app.rate-limit.email-inbound.per-address.window-seconds:3600}") long addressWindowSeconds,
            Clock clock) {
        this.perIp = new RateLimiter(ipMax, Duration.ofSeconds(ipWindowSeconds));
        this.perAddress = new RateLimiter(addressMax, Duration.ofSeconds(addressWindowSeconds));
        this.clock = clock;
    }

    /** Vượt → 429 + Retry-After. */
    public void checkIp(String remoteAddr) {
        RateLimiter.Decision decision = perIp.tryAcquire("email-inbound:" + remoteAddr, clock.instant());
        if (!decision.allowed()) {
            throw ApiException.tooManyRequests(ErrorMessage.TOO_MANY_REQUESTS, decision.retryAfterSeconds());
        }
    }

    /** Lượt cho một địa chỉ: allowed = false kèm số giây phải chờ. */
    public RateLimiter.Decision tryAddress(UUID inboxId) {
        return perAddress.tryAcquire("email-address:" + inboxId, clock.instant());
    }

    /** Xoá mọi bộ đếm (test gọi trước mỗi test). */
    public void clear() {
        perIp.clear();
        perAddress.clear();
    }
}
