package com.taskly.entity;

import com.taskly.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** M8a: folder gom dự án của workspace nhóm (spec 11.2). Xoá folder không xoá dự án (folder_id → null). */
@Entity
@Table(name = "folders")
public class Folder extends BaseEntity {

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private long sortOrder;

    protected Folder() {
        // cho JPA
    }

    public Folder(UUID workspaceId, String name, long sortOrder) {
        this.workspaceId = workspaceId;
        this.name = name;
        this.sortOrder = sortOrder;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public String getName() {
        return name;
    }

    public void rename(String newName) {
        this.name = newName;
    }

    public long getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(long sortOrder) {
        this.sortOrder = sortOrder;
    }
}
