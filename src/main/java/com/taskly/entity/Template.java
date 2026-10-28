package com.taskly.entity;

import com.taskly.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Template của người dùng (M11 "Save as template"): ảnh chụp JSON section + task đang mở của một dự án lúc lưu
 * (TemplateCodec). Không sửa được (như Todoist) — chỉ tạo / xem / áp / xoá.
 */
@Entity
@Table(name = "templates")
public class Template extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description;

    /** Khoá màu (ProjectColor) của dự án tạo từ template. */
    @Column(name = "color", nullable = false, length = 20)
    private String color;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "section_count", nullable = false)
    private int sectionCount;

    @Column(name = "task_count", nullable = false)
    private int taskCount;

    protected Template() {
        // cho JPA
    }

    public Template(UUID userId, String name, String description, String color, String content, int sectionCount,
                    int taskCount) {
        this.userId = userId;
        this.name = name;
        this.description = description;
        this.color = color;
        this.content = content;
        this.sectionCount = sectionCount;
        this.taskCount = taskCount;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getColor() {
        return color;
    }

    public String getContent() {
        return content;
    }

    public int getSectionCount() {
        return sectionCount;
    }

    public int getTaskCount() {
        return taskCount;
    }
}
