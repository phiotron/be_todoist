package com.taskly.entity;

import com.taskly.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

/** Filter đã lưu (M2): tên + query cú pháp kiểu Todoist (FilterQueryParser). Ghi sau thắng. Id có thể do client sinh. */
@Entity
@Table(name = "filters")
public class Filter extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "query", nullable = false, length = 1024)
    private String query;

    @Enumerated(EnumType.STRING)
    @Column(name = "color", nullable = false, length = 20)
    private ProjectColor color;

    @Column(name = "sort_order", nullable = false)
    private long sortOrder;

    protected Filter() {
        // cho JPA
    }

    /** Id do server sinh. */
    public Filter(UUID userId, String name, String query, ProjectColor color, long sortOrder) {
        this.userId = userId;
        this.name = name;
        this.query = query;
        this.color = color;
        this.sortOrder = sortOrder;
    }

    /** Id do client sinh (spec 12.11) — FilterService kiểm trùng / idempotent trước khi gọi. */
    public Filter(UUID id, UUID userId, String name, String query, ProjectColor color, long sortOrder) {
        this(userId, name, query, color, sortOrder);
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

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
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
