package com.taskly.entity;

import com.taskly.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Refresh token: chỉ lưu SHA-256 (token_hash). Xoay vòng: mỗi lần refresh thu hồi token cũ. */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** Họ token: login/register mở họ mới, refresh kế thừa họ; logout thu hồi cả họ. */
    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    protected RefreshToken() {
        // cho JPA
    }

    /** Token mở một họ mới. */
    public RefreshToken(User user, String tokenHash, Instant expiresAt) {
        this(user, tokenHash, expiresAt, UUID.randomUUID());
    }

    public RefreshToken(User user, String tokenHash, Instant expiresAt, UUID familyId) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.familyId = familyId;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    /** Còn dùng được: chưa bị thu hồi và chưa tới hạn. */
    public boolean isActive(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
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

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
