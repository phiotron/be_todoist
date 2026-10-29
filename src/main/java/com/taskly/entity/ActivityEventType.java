package com.taskly.entity;

/**
 * Loại sự kiện Activity log (M10, plan F5). Như Todoist: không có sự kiện chuyển dự án / sắp xếp / đổi label.
 * ARCHIVED / UNARCHIVED chỉ cho dự án và section.
 */
public enum ActivityEventType {
    ADDED,
    UPDATED,
    COMPLETED,
    UNCOMPLETED,
    DELETED,
    ARCHIVED,
    UNARCHIVED,
    /** Chờ duyệt: yêu cầu / duyệt / trả lại / huỷ yêu cầu (chỉ cho task). */
    REVIEW_REQUESTED,
    REVIEW_APPROVED,
    REVIEW_REJECTED,
    REVIEW_CANCELLED,
    REVIEW_RESTORED
}
