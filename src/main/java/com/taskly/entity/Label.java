package com.taskly.entity;

import com.taskly.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Label cá nhân (M2). Thuộc user, không thuộc workspace. Ghi sau thắng (không có version). Id có thể do client sinh
 * (BaseEntity.assignId — Persistable nên save() luôn persist đúng id, không merge).
 */
@Entity
@Table(name = "labels")
public class Label extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "color", nullable = false, length = 20)
    private ProjectColor color;

    @Column(name = "sort_order", nullable = false)
    private long sortOrder;

    protected Label() {
        // cho JPA
    }

    /** Id do server sinh. */
    public Label(UUID userId, String name, ProjectColor color, long sortOrder) {
        this.userId = userId;
        this.name = name;
        this.color = color;
        this.sortOrder = sortOrder;
    }

    /** Id do client sinh (spec 12.11) — LabelService kiểm trùng / idempotent trước khi gọi. */
    public Label(UUID id, UUID userId, String name, ProjectColor color, long sortOrder) {
        this(userId, name, color, sortOrder);
        assignId(id);
    }

    public UUID getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ProjectColor getColor() {
        return color;
    }

    public void setColor(ProjectColor color) {
        this.color = color;
    }

    public long getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(long sortOrder) {
        this.sortOrder = sortOrder;
    }
}
