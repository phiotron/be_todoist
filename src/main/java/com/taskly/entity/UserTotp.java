package com.taskly.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Secret TOTP của một user (bảng user_totp, khoá chính = user_id — spec mục 10). secretEncrypted là bản mã AES-GCM
 * (SecretCipher), KHÔNG bao giờ là secret thô. enabledAt null = đã setup, chưa bật. lastUsedStep chống dùng lại mã.
 * Không kế thừa BaseEntity (id tự sinh) → tự khai auditing; Persistable để save() của bản mới là persist.
 */
@Entity
@Table(name = "user_totp")
@EntityListeners(AuditingEntityListener.class)
public class UserTotp implements Persistable<UUID> {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "secret_encrypted", nullable = false, length = 255)
    private String secretEncrypted;

    @Column(name = "enabled_at")
    private Instant enabledAt;

    @Column(name = "last_used_step")
    private Long lastUsedStep;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserTotp() {
        // cho JPA
    }

    public UserTotp(UUID userId, String secretEncrypted) {
        this.userId = userId;
        this.secretEncrypted = secretEncrypted;
    }

    @Override
    public UUID getId() {
        return userId;
    }

    /** Bản mới (chưa persist) khi auditing chưa ghi created_at. */
    @Override
    public boolean isNew() {
        return createdAt == null;
    }

    public boolean isEnabled() {
        return enabledAt != null;
    }

    public void enable(Instant now) {
        if (enabledAt == null) {
            enabledAt = now;
        }
    }

    /** Setup lại khi chưa bật: secret mới, quay về trạng thái chờ bật, quên bước đã dùng. */
    public void replaceSecret(String newSecretEncrypted) {
        this.secretEncrypted = newSecretEncrypted;
        this.enabledAt = null;
        this.lastUsedStep = null;
    }

    /** Ghi nhận bước 30 giây vừa dùng; chỉ tiến lên (bước cũ hơn không ghi đè). */
    public void markStepUsed(long step) {
        if (lastUsedStep == null || step > lastUsedStep) {
            lastUsedStep = step;
        }
    }

    public UUID getUserId() {
        return userId;
    }

    public String getSecretEncrypted() {
        return secretEncrypted;
    }

    public Instant getEnabledAt() {
        return enabledAt;
    }

    public Long getLastUsedStep() {
        return lastUsedStep;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
