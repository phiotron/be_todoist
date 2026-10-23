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

@Entity
@Table(name = "workspaces")
public class Workspace extends BaseEntity {

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private WorkspaceType type;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    protected Workspace() {
        // cho JPA
    }

    public Workspace(String name, WorkspaceType type, User owner) {
        this.name = name;
        this.type = type;
        this.owner = owner;
    }

    public String getName() {
        return name;
    }

    public WorkspaceType getType() {
        return type;
    }

    public User getOwner() {
        return owner;
    }
}
