package com.taskly.entity;

import com.taskly.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Section của dự án. Giữ projectId dạng cột (quyết định Q4). archivedAt != null = section lưu trữ (spec 5.7). */
@Entity
@Table(name = "sections")
public class Section extends BaseEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "name", nullable = false, length = 2048)
    private String name;

    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description;

    @Column(name = "sort_order", nullable = false)
    private long sortOrder;

    @Column(name = "archived_at")
    private Instant archivedAt;

    protected Section() {
        // cho JPA
    }

    public Section(UUID projectId, String name, String description, long sortOrder) {
        this.projectId = projectId;
        this.name = name;
        this.description = description;
        this.sortOrder = sortOrder;
    }

    public void rename(String newName) {
        this.name = newName;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setSortOrder(long sortOrder) {
        this.sortOrder = sortOrder;
    }

    /**
     * M11 Move section: sang dự án khác (cùng workspace) tại vị trí mới. Task của section đi theo nhờ FK ghép
     * (project_id, section_id) ON UPDATE CASCADE (V15).
     */
    public void moveTo(UUID newProjectId, long newSortOrder) {
        this.projectId = newProjectId;
        this.sortOrder = newSortOrder;
    }

    /** Lặp lại không ghi đè thời điểm lưu trữ đầu tiên. */
    public void archive(Instant now) {
        if (archivedAt == null) {
            archivedAt = now;
        }
    }

    public void unarchive() {
        archivedAt = null;
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public long getSortOrder() {
        return sortOrder;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }
}
