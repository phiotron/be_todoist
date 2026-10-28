package com.taskly.entity;

/**
 * Giá trị tham số view (hằng viết thường = giá trị trên URL, Spring chuyển bằng Enum.valueOf). Bản 3.1: today,
 * upcoming (cửa sổ ngày), overdue, completed (màn Completed toàn cục, xuyên dự án).
 */
public enum TaskViewType {
    today, upcoming, overdue, completed,
    /** M7: lịch — task đang mở có due_day trong [from, to] (bắt buộc), xem TaskListingService.byCalendar. */
    calendar,
    /**
     * Khu "Việc nhóm" dưới Hộp thư đến (theo yêu cầu người dùng, khác Todoist): task GỐC chưa có ngày, chưa xong của các
     * dự án nhóm đã tham gia — dự án của workspace Team + dự án cá nhân có người cộng tác; không nhận Display / from / to.
     */
    inbox_team
}
