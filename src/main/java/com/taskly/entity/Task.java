package com.taskly.entity;

import com.taskly.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Công việc. Quan hệ project/section/parent giữ dạng cột UUID (quyết định Q4). workspaceId phi chuẩn = workspace của
 * dự án (đặt lúc tạo; chuyển task chỉ trong cùng workspace — spec 10).
 * dueDatetime là giờ FLOATING (LocalDateTime ↔ TIMESTAMP không múi giờ, spec 5.2). Cột sinh due_day / due_sort KHÔNG map
 * (chỉ dùng trong SQL của TaskViewRepository).
 * @Version: Hibernate tăng version chỉ khi entity thực sự đổi (dirty) → PATCH không đổi gì giữ nguyên version
 * và updated_at (BR-10, mục 5.6). sort_order KHÔNG ghi qua entity (updatable = false): đổi bằng native UPDATE
 * (TaskRepository.updateSortOrder / relocate) để KHÔNG tăng version (spec 5.6, C4).
 */
@Entity
@Table(name = "tasks")
public class Task extends BaseEntity {

    public static final int DEFAULT_PRIORITY = 4;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "section_id")
    private UUID sectionId;

    @Column(name = "parent_id")
    private UUID parentId;

    /** null khi người tạo đã xoá tài khoản (ON DELETE SET NULL, M3). */
    @Column(name = "creator_id", updatable = false)
    private UUID creatorId;

    @Column(name = "content", nullable = false, length = 500)
    private String content;

    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description;

    @Column(name = "priority", nullable = false)
    private int priority;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "due_datetime")
    private LocalDateTime dueDatetime;

    @Column(name = "due_string", length = 200)
    private String dueString;

    @Column(name = "recurrence_rule", length = 500)
    private String recurrenceRule;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @Column(name = "deadline_date")
    private LocalDate deadlineDate;

    @Column(name = "sort_order", nullable = false, updatable = false)
    private long sortOrder;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** Cùng giá trị cho các task hoàn thành trong MỘT lần gọi — căn cứ Undo (spec 5.1, 10). null = chưa xong / xong từ trước. */
    @Column(name = "completion_batch_id")
    private UUID completionBatchId;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Task() {
        // cho JPA
    }

    public Task(UUID workspaceId, UUID projectId, UUID sectionId, UUID parentId, UUID creatorId, String content,
                String description, int priority, LocalDate dueDate, LocalDateTime dueDatetime, Integer durationMinutes,
                LocalDate deadlineDate, long sortOrder) {
        this.workspaceId = workspaceId;
        this.projectId = projectId;
        this.sectionId = sectionId;
        this.parentId = parentId;
        this.creatorId = creatorId;
        this.content = content;
        this.description = description;
        this.priority = priority;
        this.dueDate = dueDate;
        this.dueDatetime = dueDatetime;
        this.durationMinutes = durationMinutes;
        this.deadlineDate = deadlineDate;
        this.sortOrder = sortOrder;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    /** Một task có tối đa một trong hai (CHECK ck_tasks_single_due). Duration do caller (TaskService.applyDue) lo. */
    public void setDue(LocalDate dueDate, LocalDateTime dueDatetime) {
        this.dueDate = dueDate;
        this.dueDatetime = dueDatetime;
    }

    /** Chỉ hợp lệ khi có dueDatetime (CHECK ck_tasks_duration). */
    public void setDurationMinutes(Integer durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public void setDeadlineDate(LocalDate deadlineDate) {
        this.deadlineDate = deadlineDate;
    }

    /** Đổi vùng chứa (move): dự án, section, cha. */
    public void placeIn(UUID projectId, UUID sectionId, UUID parentId) {
        this.projectId = projectId;
        this.sectionId = sectionId;
        this.parentId = parentId;
    }

    /**
     * Hoàn thành: ghi completed_at + completion_batch_id CHỈ khi task đang mở (không ghi đè — BR-05, task đã xong từ
     * trước giữ nguyên completed_at / batch cũ). batchId do caller sinh MỘT lần cho cả lần gọi (spec 5.1).
     */
    public void complete(Instant now, UUID batchId) {
        if (completedAt == null) {
            completedAt = now;
            completionBatchId = batchId;
        }
    }

    /** Mở lại: xoá luôn completion_batch_id (spec 5.1). */
    public void reopen() {
        completedAt = null;
        completionBatchId = null;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getSectionId() {
        return sectionId;
    }

    public UUID getParentId() {
        return parentId;
    }

    public UUID getCreatorId() {
        return creatorId;
    }

    public String getContent() {
        return content;
    }

    public String getDescription() {
        return description;
    }

    public int getPriority() {
        return priority;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public LocalDateTime getDueDatetime() {
        return dueDatetime;
    }

    public String getDueString() {
        return dueString;
    }

    public String getRecurrenceRule() {
        return recurrenceRule;
    }

    public Integer getDurationMinutes() {
        return durationMinutes;
    }

    public LocalDate getDeadlineDate() {
        return deadlineDate;
    }

    public long getSortOrder() {
        return sortOrder;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public UUID getCompletionBatchId() {
        return completionBatchId;
    }

    public long getVersion() {
        return version;
    }
}
