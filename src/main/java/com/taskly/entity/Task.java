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
import org.hibernate.annotations.DynamicUpdate;

/**
 * Công việc. Quan hệ project/section/parent giữ dạng cột UUID (quyết định Q4). workspaceId phi chuẩn = workspace của
 * dự án (đặt lúc tạo; chuyển task chỉ trong cùng workspace — spec 10).
 * dueDatetime là giờ FLOATING (LocalDateTime ↔ TIMESTAMP không múi giờ, spec 5.2). Cột sinh due_day / due_sort KHÔNG map
 * (chỉ dùng trong SQL của TaskViewRepository).
 * @Version: Hibernate tăng version chỉ khi entity thực sự đổi (dirty) → PATCH không đổi gì giữ nguyên version
 * và updated_at (BR-10, mục 5.6). sort_order KHÔNG ghi qua entity (updatable = false): đổi bằng native UPDATE
 * (TaskRepository.updateSortOrder / relocate) để KHÔNG tăng version (spec 5.6, C4).
 * @DynamicUpdate: UPDATE qua entity chỉ ghi cột ĐÃ ĐỔI → không ghi lại project_id / section_id cũ đè lên relocate (native,
 * không tăng version) của một move song song.
 */
@Entity
@DynamicUpdate
@Table(name = "tasks")
public class Task extends BaseEntity {

    public static final String REVIEW_NONE = "NONE";
    public static final String REVIEW_PENDING = "PENDING";

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

    /** M6c: `every!` = true (kỳ kế tính từ ngày hoàn thành). */
    @Column(name = "recurrence_from_completion", nullable = false)
    private boolean recurrenceFromCompletion;

    /** M6c: múi giờ cố định của due có giờ (IANA); null = floating (CHECK ck_tasks_due_timezone). */
    @Column(name = "due_timezone", length = 64)
    private String dueTimezone;

    /** M6c: ảnh chụp Undo của lần hoàn thành task lặp gần nhất (xem recordRecurrenceUndo / recordResetUndo). */
    @Column(name = "recurrence_undo_batch_id")
    private UUID recurrenceUndoBatchId;

    @Column(name = "recurrence_undo_due_date")
    private LocalDate recurrenceUndoDueDate;

    @Column(name = "recurrence_undo_due_datetime")
    private LocalDateTime recurrenceUndoDueDatetime;

    @Column(name = "recurrence_undo_completed_at")
    private Instant recurrenceUndoCompletedAt;

    /** Chờ duyệt (V22): NONE | PENDING. PENDING vẫn là việc mở (completed_at null). */
    @Column(name = "review_status", nullable = false, length = 16)
    private String reviewStatus = REVIEW_NONE;

    @Column(name = "review_requested_by")
    private UUID reviewRequestedBy;

    @Column(name = "review_requested_at")
    private Instant reviewRequestedAt;

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

    /**
     * Bản sao cho "sao chép task" (spec 10.1): chép nội dung, mô tả, ưu tiên, due, duration, deadline, due_string,
     * quy tắc lặp (M6), workspace, dự án, section. id / version (= 0) / thời gian là mới; bản sao luôn CHƯA hoàn
     * thành và KHÔNG có completion_batch_id (chỉ chép con đang mở - spec 5.4; không thừa hưởng batch Undo của bản
     * gốc). Không chép nhắc (M4), comment (M8). sort_order chỉ được truyền ở constructor (lúc tạo).
     */
    public static Task copyOf(Task source, UUID parentId, UUID creatorId, long sortOrder) {
        Task copy = new Task(source.getWorkspaceId(), source.projectId, source.sectionId, parentId, creatorId,
                source.content, source.description, source.priority, source.dueDate, source.dueDatetime,
                source.durationMinutes, source.deadlineDate, sortOrder);
        copy.dueString = source.dueString;
        copy.recurrenceRule = source.recurrenceRule;
        copy.recurrenceFromCompletion = source.recurrenceFromCompletion;
        copy.dueTimezone = source.dueTimezone;
        return copy;
    }

    /** M6c: chuỗi người dùng gõ (Quick Add), lưu nguyên văn; null = không có. */
    public void setDueString(String dueString) {
        this.dueString = dueString;
    }

    /** M6c: rule đã chuẩn hoá (RecurrenceRule.format) hoặc null = bỏ lặp. Caller lo "rule cần due". */
    public void setRecurrence(String recurrenceRule, boolean fromCompletion) {
        this.recurrenceRule = recurrenceRule;
        this.recurrenceFromCompletion = recurrenceRule != null && fromCompletion;
    }

    /** M6c: chỉ hợp lệ khi có dueDatetime (CHECK ck_tasks_due_timezone). */
    public void setDueTimezone(String dueTimezone) {
        this.dueTimezone = dueTimezone;
    }

    public boolean isRecurring() {
        return recurrenceRule != null;
    }

    /**
     * Hoàn thành MỘT LẦN task lặp (spec 5.3): KHÔNG đặt completed_at, dời due sang kỳ kế (giữ duration, deadline,
     * due_timezone); ghi ảnh chụp Undo = due cũ + batchId của lần gọi.
     */
    public void advanceRecurrence(LocalDate nextDate, LocalDateTime nextDatetime, UUID batchId) {
        this.recurrenceUndoBatchId = batchId;
        this.recurrenceUndoDueDate = dueDate;
        this.recurrenceUndoDueDatetime = dueDatetime;
        this.recurrenceUndoCompletedAt = null;
        clearReview();
        this.dueDate = nextDate;
        this.dueDatetime = nextDatetime;
    }

    /** Con bị "reset sub-tasks": nhớ completed_at cũ cho Undo rồi mở lại. */
    public void resetForRecurrence(UUID batchId) {
        this.recurrenceUndoBatchId = batchId;
        this.recurrenceUndoCompletedAt = completedAt;
        this.recurrenceUndoDueDate = null;
        this.recurrenceUndoDueDatetime = null;
        reopen();
    }

    /** Undo hoàn thành task lặp: due cũ quay lại. Không có ảnh chụp → false (không đổi gì). */
    public boolean undoRecurrence() {
        if (recurrenceUndoBatchId == null || (recurrenceUndoDueDate == null && recurrenceUndoDueDatetime == null)) {
            return false;
        }
        this.dueDate = recurrenceUndoDueDate;
        this.dueDatetime = recurrenceUndoDueDatetime;
        clearRecurrenceUndo();
        return true;
    }

    /** Undo "reset sub-tasks": con còn đang mở → hoàn thành lại với completed_at cũ (không batch: "xong từ trước"). */
    public boolean undoReset(UUID batchId) {
        if (batchId == null || !batchId.equals(recurrenceUndoBatchId) || recurrenceUndoCompletedAt == null) {
            return false;
        }
        boolean changed = completedAt == null;
        if (changed) {
            completedAt = recurrenceUndoCompletedAt;
            completionBatchId = null;
        }
        clearRecurrenceUndo();
        return changed;
    }

    /** PATCH đổi due / lặp, hoặc lần hoàn thành sau: ảnh chụp cũ hết hiệu lực. */
    public void clearRecurrenceUndo() {
        this.recurrenceUndoBatchId = null;
        this.recurrenceUndoDueDate = null;
        this.recurrenceUndoDueDatetime = null;
        this.recurrenceUndoCompletedAt = null;
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
            clearRecurrenceUndo(); // M6c: Undo của lần nhảy kỳ trước không còn đúng khi task đã hoàn thành thật
            clearReview();
        }
    }

    public boolean isOpen() {
        return completedAt == null;
    }

    public boolean isReviewPending() {
        return REVIEW_PENDING.equals(reviewStatus);
    }

    /** Member / Guest tick hoàn thành ở dự án nhóm: chờ Admin duyệt. */
    public void requestReview(UUID userId, Instant now) {
        this.reviewStatus = REVIEW_PENDING;
        this.reviewRequestedBy = userId;
        this.reviewRequestedAt = now;
    }

    /** Huỷ / trả lại / duyệt xong / hoàn thành thật: về NONE (chỉ ghi khi có gì để xoá). */
    /** Hoàn tác "Trả lại": khôi phục PENDING với người yêu cầu + thời điểm cũ. */
    public void restoreReview(UUID requestedBy, Instant requestedAt) {
        this.reviewStatus = REVIEW_PENDING;
        this.reviewRequestedBy = requestedBy;
        this.reviewRequestedAt = requestedAt;
    }

    public void clearReview() {
        if (isReviewPending() || reviewRequestedBy != null || reviewRequestedAt != null) {
            this.reviewStatus = REVIEW_NONE;
            this.reviewRequestedBy = null;
            this.reviewRequestedAt = null;
        }
    }

    public String getReviewStatus() {
        return reviewStatus;
    }

    public UUID getReviewRequestedBy() {
        return reviewRequestedBy;
    }

    public Instant getReviewRequestedAt() {
        return reviewRequestedAt;
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

    public boolean isRecurrenceFromCompletion() {
        return recurrenceFromCompletion;
    }

    public String getDueTimezone() {
        return dueTimezone;
    }

    public UUID getRecurrenceUndoBatchId() {
        return recurrenceUndoBatchId;
    }
}
