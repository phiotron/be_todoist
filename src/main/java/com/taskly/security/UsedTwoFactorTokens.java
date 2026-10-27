package com.taskly.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * jti của twoFactorToken đã dùng (spec m10: dùng một lần). Bộ nhớ, tự dọn mục hết hạn: giữ tới exp + 2 phút (dung sai
 * đồng hồ của decoder là 60 giây). Mất khi khởi động lại và không chia sẻ nhiều instance — chấp nhận ở vòng 1.
 */
@Component
public class UsedTwoFactorTokens {

    private static final Duration KEEP_AFTER_EXPIRY = Duration.ofMinutes(2);

    private final Clock clock;
    private final ConcurrentHashMap<String, Instant> used = new ConcurrentHashMap<>();

    public UsedTwoFactorTokens(Clock clock) {
        this.clock = clock;
    }

    /** true nếu jti đã được đánh dấu dùng (token hết hạn thì decoder đã từ chối trước khi tới đây). */
    public boolean isUsed(String jti) {
        return used.containsKey(jti);
    }

    /** true nếu jti chưa dùng (đánh dấu luôn, nguyên tử); false nếu đã dùng. */
    public boolean markUsed(String jti, Instant tokenExpiresAt) {
        Instant now = Instant.now(clock);
        used.values().removeIf(keepUntil -> keepUntil.isBefore(now));
        return used.putIfAbsent(jti, tokenExpiresAt.plus(KEEP_AFTER_EXPIRY)) == null;
    }
}
