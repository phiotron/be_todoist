package com.taskly.entity;

import com.taskly.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

/** Yêu thích của một user với một dự án (M1) / label / filter (M2) — đa hình, không FK tới đích. */
@Entity
@Table(name = "favorites")
public class Favorite extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 10)
    private FavoriteTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    protected Favorite() {
        // cho JPA
    }

    public Favorite(UUID userId, FavoriteTargetType targetType, UUID targetId) {
        this.userId = userId;
        this.targetType = targetType;
        this.targetId = targetId;
    }

    public UUID getUserId() {
        return userId;
    }

    public FavoriteTargetType getTargetType() {
        return targetType;
    }

    public UUID getTargetId() {
        return targetId;
    }
}
