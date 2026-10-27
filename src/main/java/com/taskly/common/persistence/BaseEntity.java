package com.taskly.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Lớp cha của mọi entity: id UUID + created_at/updated_at.
 * - id: ỨNG DỤNG gán (không @GeneratedValue): mặc định UUID.randomUUID() ngay khi tạo đối tượng; client có thể gán id
 *   riêng bằng assignId(...) TRƯỚC khi lưu (projects / sections / tasks, M2 labels / filters — spec 12.11).
 * - Persistable: Spring Data chỉ gọi persist() cho entity MỚI (cờ isNew, true tới khi persist / load xong). Không có
 *   Persistable thì save() thấy id != null và gọi merge() → SELECT thừa + lỗi với @Version.
 * - createdAt/updatedAt: Spring Data JPA Auditing ghi, lấy giờ từ Clock (JpaAuditingConfig).
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean isNew = true;

    /** Id do client sinh (null = giữ id server sinh). Chỉ gọi trên entity MỚI, trước khi lưu. */
    public void assignId(UUID clientId) {
        if (!isNew) {
            throw new IllegalStateException("Chỉ gán id cho entity chưa lưu");
        }
        if (clientId != null) {
            this.id = clientId;
        }
    }

    /** Lưới an toàn: entity do Hibernate dựng bằng constructor rỗng vẫn có id trước khi persist. */
    @PrePersist
    void ensureId() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        isNew = false;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
