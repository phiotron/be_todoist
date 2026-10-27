package com.taskly.security;

import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Giới hạn "quên mật khẩu" theo email (mặc định 5 lần / 3600 giây — spec mục 5.9). Cùng mẫu AuthRateLimiter (M1):
 * bọc RateLimiter (cửa sổ trượt, bộ đếm trong bộ nhớ — chỉ đúng với MỘT instance), giờ lấy từ bean Clock (test dời được).
 * Key = "password-reset:" + SHA-256(email đã chuẩn hoá): bộ đếm không giữ email thô. Đếm cả email không có tài khoản
 * để phản hồi không lộ email nào tồn tại.
 */
@Component
public class PasswordResetRateLimiter {

    static final String KEY_PREFIX = "password-reset:";

    private final RateLimiter limiter;
    private final Clock clock;

    public PasswordResetRateLimiter(@Value("${app.rate-limit.password-reset.max-requests:5}") int maxRequests,
                                    @Value("${app.rate-limit.password-reset.window-seconds:3600}") long windowSeconds,
                                    Clock clock) {
        this.limiter = new RateLimiter(maxRequests, Duration.ofSeconds(windowSeconds));
        this.clock = clock;
    }

    /** true = còn trong hạn mức (lần này đã được tính); false = vượt — caller vẫn trả 200, không gửi thư. */
    public boolean tryAcquire(String normalizedEmail) {
        return limiter.tryAcquire(KEY_PREFIX + OpaqueTokens.sha256Hex(normalizedEmail), clock.instant()).allowed();
    }

    /** Xoá mọi bộ đếm (IntegrationTestBase.resetRateLimits() gọi trước mỗi test). */
    public void clear() {
        limiter.clear();
    }
}
