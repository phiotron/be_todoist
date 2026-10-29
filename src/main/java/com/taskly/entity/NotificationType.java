package com.taskly.entity;

/** Loại thông báo trong hộp thông báo (M8b, CHECK ck_notifications_type ở V12). Đợt sau (M8a / giao việc) mở thêm. */
public enum NotificationType {
    /** Được @mention trong một comment. */
    COMMENT_MENTION,
    /** Chờ duyệt: Member / Guest yêu cầu duyệt một việc (gửi mọi Admin của workspace). */
    REVIEW_REQUESTED,
    /** Chờ duyệt: Admin đã duyệt việc (gửi người yêu cầu). */
    REVIEW_APPROVED,
    /** Chờ duyệt: Admin trả lại việc (gửi người yêu cầu). */
    REVIEW_REJECTED
}
