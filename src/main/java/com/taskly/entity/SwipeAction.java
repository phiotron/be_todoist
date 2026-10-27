package com.taskly.entity;

/**
 * Hành động khi vuốt một task (users.swipe_left / swipe_right, spec 9.2). REMINDERS mở phần nhắc của task (dùng thật
 * từ M4 — M3 vẫn cho lưu). DELETE luôn có hộp xác nhận ở app (BR-11).
 */
public enum SwipeAction {
    COMPLETE,
    SCHEDULE,
    DELETE,
    REMINDERS,
    SELECT,
    NONE
}
