package com.taskly.common.error;

/**
 * Câu lỗi nghiệp vụ dùng làm "detail" của ProblemDetail. Mỗi hằng là một key trong messages_vi.properties và
 * messages_en.properties; ProblemDetailLocalizer dịch theo Accept-Language (vi mặc định).
 * Thêm hằng mới thì thêm key vào CẢ HAI file properties (MessageBundlesTest kiểm).
 */
public enum ErrorMessage {
    VALIDATION_FAILED("error.validation-failed"),
    SESSION_INVALID("error.session-invalid"),
    INVALID_CREDENTIALS("error.invalid-credentials"),
    EMAIL_TAKEN("error.email-taken"),
    DATA_CONFLICT("error.data-conflict"),
    INTERNAL_ERROR("error.internal"),
    CURRENT_PASSWORD_INCORRECT("error.current-password-incorrect"),
    RESET_TOKEN_INVALID("error.reset-token-invalid"),
    RESET_TOKEN_EXPIRED("error.reset-token-expired"),
    MAIL_UNAVAILABLE("error.mail-unavailable"),

    // ---- M1 (sinh tu ProblemTexts cua M1 o Task 13 Step 5c)
    FORBIDDEN("error.forbidden"),
    INVALID_VALUE("validation.invalid"),
    CONCURRENT_UPDATE("error.concurrent-update"),
    TOO_MANY_REQUESTS("error.too-many-requests"),
    LIMIT_REACHED("error.limit-reached"),
    ID_CONFLICT("error.id-conflict"),
    DESCRIPTION_SIZE("error.description-size"),
    INVALID_CURSOR("error.invalid-cursor"),
    INVALID_LIMIT("error.invalid-limit"),
    PROJECT_NOT_FOUND("error.project-not-found"),
    PROJECT_NAME_SIZE("error.project-name-size"),
    PROJECT_ARCHIVED("error.project-archived"),
    INBOX_PROTECTED("error.inbox-protected"),
    INBOX_NOT_NESTABLE("error.inbox-not-nestable"),
    PROJECT_TOO_DEEP("error.project-too-deep"),
    PROJECT_CYCLE("error.project-cycle"),
    TARGET_ARCHIVED("error.target-archived"),
    CROSS_WORKSPACE_MOVE("error.cross-workspace-move"),
    MOVE_PARENT_ID_REQUIRED("error.move-parent-id-required"),
    SECTION_NOT_FOUND("error.section-not-found"),
    SECTION_NAME_SIZE("error.section-name-size"),
    SECTION_NOT_IN_PROJECT("error.section-not-in-project"),
    SECTION_ARCHIVED("error.section-archived"),
    TASK_NOT_FOUND("error.task-not-found"),
    PROJECT_ID_REQUIRED("error.project-id-required"),
    TASK_CONTENT_SIZE("error.task-content-size"),
    PRIORITY_RANGE("error.priority-range"),
    DUE_CONFLICT("error.due-conflict"),
    DURATION_RANGE("error.duration-range"),
    DURATION_NEEDS_TIME("error.duration-needs-time"),
    VERSION_REQUIRED("error.version-required"),
    VERSION_CONFLICT("error.version-conflict"),
    SUBTASK_TOO_DEEP("error.subtask-too-deep"),
    SUBTASK_CYCLE("error.subtask-cycle"),
    PARENT_NOT_IN_PROJECT("error.parent-not-in-project"),
    PARENT_COMPLETED("error.parent-completed"),
    PARENT_NOT_IN_SECTION("error.parent-not-in-section"),
    TASK_COMPLETED("error.task-completed"),
    LIST_QUERY_INVALID("error.list-query-invalid"),
    FROM_ONLY_UPCOMING("error.from-only-upcoming"),
    SINCE_ONLY_COMPLETED("error.since-only-completed"),
    COMPLETED_RANGE_INVALID("error.completed-range-invalid"),
    UPCOMING_WINDOW_INVALID("error.upcoming-window-invalid"),
    COMPLETED_ONLY_PROJECT("error.completed-only-project"),
    REORDER_TYPE_REQUIRED("error.reorder-type-required"),
    REORDER_ID_REQUIRED("error.reorder-id-required"),
    AFTER_ID_NOT_IN_CONTAINER("error.after-id-not-in-container"),
    REORDER_AFTER_ID_REQUIRED("error.reorder-after-id-required"),
    BATCH_ACTION_REQUIRED("error.batch-action-required"),
    BATCH_IDS_SIZE("error.batch-ids-size"),
    BATCH_IDS_DUPLICATE("error.batch-ids-duplicate"),
    BATCH_FIELDS_INVALID("error.batch-fields-invalid"),
    RESCHEDULE_DATE_REQUIRED("error.reschedule-date-required"),
    RESCHEDULE_DATE_PAST("error.reschedule-date-past"),
    IDEMPOTENCY_KEY_INVALID("error.idempotency-key-invalid"),
    IDEMPOTENCY_IN_PROGRESS("error.idempotency-in-progress"),
    IDEMPOTENCY_MISMATCH("error.idempotency-mismatch"),
    SEARCH_QUERY_TOO_LONG("error.search-query-too-long"),
    LABEL_NOT_FOUND("error.label-not-found"),
    LABEL_NAME_SIZE("error.label-name-size"),
    LABEL_NAME_CHARS("error.label-name-chars"),
    LABEL_NAME_TAKEN("error.label-name-taken"),
    LABELS_PER_TASK("error.labels-per-task"),
    FILTER_NOT_FOUND("error.filter-not-found"),
    FILTER_NAME_SIZE("error.filter-name-size"),
    FILTER_QUERY_SIZE("error.filter-query-size"),
    FILTER_SYNTAX("error.filter-syntax"),
    VIEW_KEY_INVALID("error.view-key-invalid"),
    DISPLAY_INVALID("error.display-invalid"),
    TASK_LIST_SELECTOR("error.task-list-selector"),
    BATCH_LABEL_IDS("error.batch-label-ids"),
    // ---- M7 Layout
    CALENDAR_WINDOW_INVALID("error.calendar-window-invalid"),
    // ---- Nhắc việc (M4)
    REMINDER_NOT_FOUND("error.reminder-not-found"),
    REMINDER_FIELDS_MISMATCH("error.reminder-fields-mismatch"),
    REMINDER_AT_PAST("error.reminder-at-past"),
    REMINDER_NEEDS_DUE_TIME("error.reminder-needs-due-time"),
    REMINDER_TASK_COMPLETED("error.reminder-task-completed"),
    REMINDER_LIMIT_RANGE("error.reminder-limit-range"),
    TWO_FACTOR_REQUIRED("error.two-factor-required"),
    TWO_FACTOR_TOKEN_INVALID("error.two-factor-token-invalid"),
    TWO_FACTOR_CODE_INVALID("error.two-factor-code-invalid"),
    TWO_FACTOR_ALREADY_ENABLED("error.two-factor-already-enabled"),
    TWO_FACTOR_NOT_SETUP("error.two-factor-not-setup"),
    TWO_FACTOR_NOT_ENABLED("error.two-factor-not-enabled"),
    PASSWORD_REQUIRED("error.password-required"),
    // M5 - đăng nhập / liên kết Google
    GOOGLE_INVALID_ID_TOKEN("error.google.invalid-id-token"),
    GOOGLE_INVALID_TOKEN_FOR_LINK("error.google.invalid-token-for-link"),
    GOOGLE_EXACTLY_ONE_TOKEN("error.google.exactly-one-token"),
    GOOGLE_EMAIL_NOT_VERIFIED("error.google.email-not-verified"),
    GOOGLE_LINK_TOKEN_OTHER_ACCOUNT("error.google.link-token-other-account"),
    GOOGLE_LINK_REQUIRED("error.google.link-required"),
    GOOGLE_LINKED_ELSEWHERE("error.google.linked-elsewhere"),
    GOOGLE_ACCOUNT_ALREADY_LINKED("error.google.account-already-linked"),
    GOOGLE_PASSWORD_REQUIRED_BEFORE_UNLINK("error.google.password-required-before-unlink"),
    GOOGLE_NOT_LINKED("error.google.not-linked"),
    GOOGLE_UNAVAILABLE("error.google.unavailable"),
    PASSWORD_NOT_SET("error.password.not-set"),
    // M6b - Quick Add / Date sheet
    DAY_COUNTS_RANGE_INVALID("error.day-counts-range-invalid"),
    // M6c - viec lap
    RECURRENCE_INVALID("error.recurrence-invalid"),
    RECURRENCE_REQUIRES_DUE("error.recurrence-requires-due"),
    DUE_TIMEZONE_INVALID("error.due-timezone-invalid");

    private final String key;

    ErrorMessage(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
