package com.taskly.security;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Giới hạn số lần thử THEO TÀI KHOẢN (mặc định 5 lần / 300 giây — spec mục 5.9). Cùng mẫu AuthRateLimiter (M1): bọc
 * RateLimiter (cửa sổ trượt, bộ đếm trong bộ nhớ — chỉ đúng với MỘT instance), giờ lấy từ bean Clock (test dời được).
 * Vượt → 429 kèm Retry-After. Mỗi lần thử (kể cả lần đúng) được tính một lần; không rollback theo transaction.
 * Hai bộ đếm tách nhau: mã 2FA (dùng chung cho mọi chỗ nhận mã — auth/2fa/verify, me/2fa/enable | disable | recovery-codes,
 * DELETE me) và mật khẩu (đoán mật khẩu qua endpoint có JWT: DELETE me, me/2fa/disable).
 */
@Component
public class TwoFactorRateLimiter {

    static final String CODE_PREFIX = "two-factor-code:";
    static final String PASSWORD_PREFIX = "account-password:";

    private final RateLimiter codeLimiter;
    private final RateLimiter passwordLimiter;
    private final Clock clock;

    public TwoFactorRateLimiter(@Value("${app.rate-limit.two-factor.max-requests:5}") int maxRequests,
                                @Value("${app.rate-limit.two-factor.window-seconds:300}") long windowSeconds,
                                @Value("${app.rate-limit.account-password.max-requests:5}") int passwordMaxRequests,
                                @Value("${app.rate-limit.account-password.window-seconds:300}") long passwordWindowSeconds,
                                Clock clock) {
        this.codeLimiter = new RateLimiter(maxRequests, Duration.ofSeconds(windowSeconds));
        this.passwordLimiter = new RateLimiter(passwordMaxRequests, Duration.ofSeconds(passwordWindowSeconds));
        this.clock = clock;
    }

    /** Tính một lần thử mã 2FA của tài khoản; vượt hạn mức → ApiException 429 kèm Retry-After. */
    public void checkCode(UUID userId) {
        enforce(codeLimiter, CODE_PREFIX + userId);
    }

    /** Tính một lần thử mật khẩu của tài khoản (DELETE me, me/2fa/disable); vượt hạn mức → 429 kèm Retry-After. */
    public void checkPassword(UUID userId) {
        enforce(passwordLimiter, PASSWORD_PREFIX + userId);
    }

    private void enforce(RateLimiter limiter, String key) {
        RateLimiter.Decision decision = limiter.tryAcquire(key, clock.instant());
        if (!decision.allowed()) {
            throw ApiException.tooManyRequests(ErrorMessage.TOO_MANY_REQUESTS, decision.retryAfterSeconds());
        }
    }

    /** Xoá mọi bộ đếm (IntegrationTestBase.resetRateLimits() gọi trước mỗi test). */
    public void clear() {
        codeLimiter.clear();
        passwordLimiter.clear();
    }
}
