package com.taskly.integration.calendar;

/** Nhà cung cấp lịch từ chối token (thu hồi quyền, refresh token hết hạn) → kết nối cần nối lại. */
public class CalendarAuthException extends RuntimeException {

    public CalendarAuthException() {
        super("calendar authorization revoked", null, false, false);
    }
}
