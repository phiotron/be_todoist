package com.taskly.entity;

/**
 * Một dòng công tắc trong cài đặt Project / Workspace notifications (M8b). Danh sách theo app Todoist chưa kiểm trên
 * máy thật [CXM]. Lượt này chỉ COMMENT_MENTION có tác dụng (nguồn thông báo duy nhất); các cờ khác chỉ lưu.
 */
public enum NotificationEvent {
    COMMENT_MENTION(NotificationScope.PROJECT),
    COMMENT_ADDED(NotificationScope.PROJECT),
    TASK_ASSIGNED(NotificationScope.PROJECT),
    TASK_COMPLETED(NotificationScope.PROJECT),
    MEMBER_JOINED(NotificationScope.WORKSPACE),
    INVITATION_ACCEPTED(NotificationScope.WORKSPACE);

    private final NotificationScope scope;

    NotificationEvent(NotificationScope scope) {
        this.scope = scope;
    }

    public NotificationScope scope() {
        return scope;
    }
}
