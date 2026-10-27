package com.taskly.entity;

import com.taskly.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Token đặt lại mật khẩu: chỉ lưu SHA-256 (token_hash), dùng một lần (used_at), hết hạn 30 phút (expires_at).
 * Vô hiệu token cũ = đặt expires_at = thời điểm vô hiệu → dùng lại trả 410 như token hết hạn.
 */
@Entity
@Table(name = "password_reset_tokens")
public class PasswordResetToken extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    protected PasswordResetToken() {
        // cho JPA
    }

    public PasswordResetToken(User user, String tokenHash, Instant expiresAt) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    /** Còn dùng được: chưa dùng và chưa tới hạn (đúng lúc expires_at là đã hết hạn). */
    public boolean isUsable(Instant now) {
        return usedAt == null && now.isBefore(expiresAt);
    }

    /** Đánh dấu đã dùng; gọi lại không ghi đè thời điểm dùng đầu tiên. */
    public void markUsed(Instant now) {
        if (usedAt == null) {
            usedAt = now;
        }
    }

    /** Token còn dùng được thì hết hạn ngay tại now; token đã dùng / đã hết hạn giữ nguyên. */
    public void invalidate(Instant now) {
        if (isUsable(now)) {
            expiresAt = now;
        }
    }

    public User getUser() {
        return user;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }
}
