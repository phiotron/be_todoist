package com.taskly.entity;

/** Loại thông báo trong hộp thông báo (M8b, CHECK ck_notifications_type ở V12). Đợt sau (M8a / giao việc) mở thêm. */
public enum NotificationType {
    /** Được @mention trong một comment. */
    COMMENT_MENTION
}
