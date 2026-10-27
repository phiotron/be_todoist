package com.taskly.common.error;

/**
 * Giá trị thuộc tính "code" của ProblemDetail (spec 12.10) — chuỗi máy đọc, KHÔNG dịch, app phân biệt tình huống bằng
 * (status, code). Gắn bằng ApiException.withCode(ProblemCodes.X). Mốc sau thêm hằng vào đây (M2 filter-syntax,
 * label-name-taken; M3 two-factor-*, password-required; M5 link-required).
 */
public final class ProblemCodes {

    public static final String EMAIL_TAKEN = "email-taken";
    public static final String VERSION_CONFLICT = "version-conflict";
    public static final String PROJECT_ARCHIVED = "project-archived";
    public static final String SECTION_ARCHIVED = "section-archived";
    public static final String PARENT_COMPLETED = "parent-completed";
    /** M1: tasks/batch SCHEDULE / MOVE có task đã hoàn thành (409); M4 dùng thêm cho nhắc của task đã xong. */
    public static final String TASK_COMPLETED = "task-completed";
    public static final String TARGET_ARCHIVED = "target-archived";
    public static final String LIMIT_REACHED = "limit-reached";
    /** `id` client-sinh trùng id có sẵn nhưng khác nội dung hoặc của người khác (spec 12.10, 12.11). */
    public static final String ID_CONFLICT = "id-conflict";
    public static final String IDEMPOTENCY_IN_PROGRESS = "idempotency-in-progress";
    public static final String IDEMPOTENCY_MISMATCH = "idempotency-mismatch";

    private ProblemCodes() {
    }
}
