package com.taskly.common.error;

/**
 * Giá trị thuộc tính "code" của ProblemDetail (spec mục 12.10) — app phân biệt tình huống bằng (status, code), không
 * bằng câu chữ (câu chữ đổi theo ngôn ngữ). M3 dùng các mã two-factor-* / password-required; M1 có mã riêng (nhóm cuối).
 */
public final class ProblemCodes {

    /** Tên thuộc tính trong ProblemDetail. */
    public static final String PROPERTY = "code";

    /** 401 ở auth/login (và auth/google từ M5): tài khoản bật 2FA, kèm "twoFactorToken". */
    public static final String TWO_FACTOR_REQUIRED = "two-factor-required";

    /** 409 ở me/2fa/setup | enable khi 2FA đang bật. */
    public static final String TWO_FACTOR_ALREADY_ENABLED = "two-factor-already-enabled";

    /** 409 ở me/2fa/enable khi chưa setup; ở me/2fa/disable | recovery-codes khi 2FA chưa bật. */
    public static final String TWO_FACTOR_NOT_SETUP = "two-factor-not-setup";

    /**
     * 401 ở auth/2fa/verify: mã 6 số / mã khôi phục sai hoặc đã dùng, twoFactorToken CÒN SỐNG (không bị đốt) → app cho
     * nhập lại mã. Người dùng chọn phương án A (2026-10-27).
     */
    public static final String TWO_FACTOR_CODE_INVALID = "two-factor-code-invalid";

    /**
     * 401 ở auth/2fa/verify: twoFactorToken hết hạn / không hợp lệ / đã dùng / sai typ-aud → app phải quay về Đăng nhập.
     */
    public static final String TWO_FACTOR_TOKEN_INVALID = "two-factor-token-invalid";

    /** 409 ở DELETE me (và bỏ liên kết Google từ M5) khi tài khoản chưa có mật khẩu. */
    public static final String PASSWORD_REQUIRED = "password-required";

    /** 409 ở auth/google khi email Google trùng tài khoản mật khẩu, kèm "linkToken" (M5). */
    public static final String LINK_REQUIRED = "link-required";

    // ---- M1 (gộp từ feature/m1-backend)
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
    // ---- M2 (spec 12.10)
    public static final String FILTER_SYNTAX = "filter-syntax";
    public static final String LABEL_NAME_TAKEN = "label-name-taken";
    // ---- M6c (contract 8.1)
    /** 400: recurrenceRule ngoài tập con RRULE rút gọn. */
    public static final String RECURRENCE_INVALID = "recurrence-invalid";
    /** 400: có recurrenceRule mà task không có due. */
    public static final String RECURRENCE_REQUIRES_DUE = "recurrence-requires-due";
    // ---- M9 (POST sync — plan 2026-10-27-m9-chuc-nang.md F2 / F4)
    /** Lỗi một lệnh: type không có handler (400). */
    public static final String SYNC_COMMAND_UNKNOWN = "sync-command-unknown";
    /** syncToken sai dạng (400 cả request). */
    public static final String SYNC_TOKEN_INVALID = "sync-token-invalid";
    /** Chỉ khi taskly.sync.conflict-policy = version: task_update gửi version cũ (409, kèm "current"). */
    public static final String SYNC_CONFLICT = "sync-conflict";
    // ---- M13b (Calendar)
    /** 409 ở me/calendar/connect: tài khoản đã nối một lịch (chỉ 1 nhà cung cấp / 1 tài khoản). */
    public static final String CALENDAR_ALREADY_CONNECTED = "calendar-already-connected";
    /** 503: chưa cấu hình client id / secret của nhà cung cấp lịch. */
    public static final String CALENDAR_NOT_CONFIGURED = "calendar-not-configured";
    /** 503: không gọi được API lịch của nhà cung cấp. */
    public static final String CALENDAR_UNAVAILABLE = "calendar-unavailable";

    // ---- M13c (OAuth cho ứng dụng bên thứ ba)
    /** 403: token OAuth thiếu scope cho request, hoặc endpoint không mở cho token OAuth. */
    public static final String INSUFFICIENT_SCOPE = "insufficient-scope";
    public static final String OAUTH_INVALID_REQUEST = "oauth-invalid-request";
    public static final String OAUTH_INVALID_GRANT = "oauth-invalid-grant";
    public static final String OAUTH_INVALID_CLIENT = "oauth-invalid-client";
    public static final String OAUTH_INVALID_SCOPE = "oauth-invalid-scope";
    public static final String OAUTH_UNSUPPORTED_GRANT_TYPE = "oauth-unsupported-grant-type";
    // ---- M8a (contract mục 2: invitation-expired; còn lại plan M8a)
    /** 410: lời mời email hết hạn / đã dùng / đã từ chối. */
    public static final String INVITATION_EXPIRED = "invitation-expired";
    /** 409: mời / thêm người đã là thành viên. */
    public static final String ALREADY_MEMBER = "already-member";
    /** 409: thao tác làm workspace nhóm không còn Admin nào. */
    public static final String LAST_ADMIN = "last-admin";
    /** 409: người cuối cùng rời dự án Restricted (dự án sẽ mồ côi). */
    public static final String LAST_PROJECT_MEMBER = "last-project-member";
    // ---- PERM (quy tắc người dùng chốt 28/10, khác Todoist)
    /** 403: Thành viên / Khách của dự án NHÓM chỉ được xem + đánh dấu hoàn thành / mở lại; mọi ghi khác chỉ Admin. */
    public static final String TEAM_ADMIN_ONLY = "team-admin-only";

    /** 409: duyệt / trả lại một việc không ở trạng thái chờ duyệt. */
    public static final String REVIEW_NOT_PENDING = "review-not-pending";

    private ProblemCodes() {
    }
}
