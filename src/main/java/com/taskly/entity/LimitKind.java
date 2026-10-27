package com.taskly.entity;

/**
 * Loại giới hạn theo gói Pro (spec 2.1) — giá trị "limit" trong ProblemDetail 422 limit-reached; app hiện câu i18n
 * theo giá trị này. M8 thêm PROJECT_COLLABORATORS, M11 thêm TEMPLATES.
 */
public enum LimitKind {
    ACTIVE_PROJECTS,
    PROJECTS,
    PROJECT_ACTIVE_TASKS,
    PROJECT_SECTIONS,
    /** Tổng section của một dự án, KỂ CẢ lưu trữ (≤ 100 — spec 2.1, I1); kiểm khi tạo section. */
    PROJECT_SECTIONS_TOTAL,
    LABELS,
    FILTERS,
    REMINDERS
}
