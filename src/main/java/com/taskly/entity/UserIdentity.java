package com.taskly.entity;

import com.taskly.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Một liên kết đăng nhập ngoài của user (M5). subject = claim "sub" của Google — định danh ổn định, KHÔNG dùng email
 * làm định danh (email Google có thể đổi). Chỉ tạo qua User.linkIdentity (aggregate User, cascade ALL).
 */
@Entity
@Table(name = "user_identities")
public class UserIdentity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private IdentityProvider provider;

    @Column(name = "subject", nullable = false, length = 255)
    private String subject;

    /** Email Google tại thời điểm liên kết (đã chuẩn hoá). */
    @Column(name = "email", nullable = false, length = 254)
    private String email;

    protected UserIdentity() {
        // cho JPA
    }

    UserIdentity(User user, IdentityProvider provider, String subject, String email) {
        this.user = user;
        this.provider = provider;
        this.subject = subject;
        this.email = email;
    }

    public User getUser() {
        return user;
    }

    public IdentityProvider getProvider() {
        return provider;
    }

    public String getSubject() {
        return subject;
    }

    public String getEmail() {
        return email;
    }
}
