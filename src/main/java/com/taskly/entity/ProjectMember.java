package com.taskly.entity;

import com.taskly.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * M8a: người cộng tác của dự án cá nhân / người đã tham gia dự án nhóm. Chủ workspace cá nhân KHÔNG có hàng (ngầm định
 * là chủ dự án); người tạo dự án nhóm có hàng.
 */
@Entity
@Table(name = "project_members")
public class ProjectMember extends BaseEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    protected ProjectMember() {
        // cho JPA
    }

    public ProjectMember(UUID projectId, UUID userId) {
        this.projectId = projectId;
        this.userId = userId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getUserId() {
        return userId;
    }
}
