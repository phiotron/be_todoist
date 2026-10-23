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
import java.time.Instant;

@Entity
@Table(name = "projects")
public class Project extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "color", length = 20)
    private String color;

    @Column(name = "is_inbox", nullable = false)
    private boolean inbox;

    @Enumerated(EnumType.STRING)
    @Column(name = "view_style", nullable = false, length = 10)
    private ViewStyle viewStyle;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** Vòng 1 không có thao tác lưu trữ → luôn null. */
    @Column(name = "archived_at")
    private Instant archivedAt;

    protected Project() {
        // cho JPA
    }

    public Project(Workspace workspace, String name, String color, boolean inbox, ViewStyle viewStyle, int sortOrder) {
        this.workspace = workspace;
        this.name = name;
        this.color = color;
        this.inbox = inbox;
        this.viewStyle = viewStyle;
        this.sortOrder = sortOrder;
    }

    public Workspace getWorkspace() {
        return workspace;
    }

    public String getName() {
        return name;
    }

    public String getColor() {
        return color;
    }

    public boolean isInbox() {
        return inbox;
    }

    public ViewStyle getViewStyle() {
        return viewStyle;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }
}
