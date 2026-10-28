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
    UNARCHIVED
}
