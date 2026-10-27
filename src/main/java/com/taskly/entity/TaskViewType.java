package com.taskly.entity;

/**
 * Giá trị tham số view (hằng viết thường = giá trị trên URL, Spring chuyển bằng Enum.valueOf). Bản 3.1: today,
 * upcoming (cửa sổ ngày), overdue, completed (màn Completed toàn cục, xuyên dự án).
 */
public enum TaskViewType {
    today, upcoming, overdue, completed
}
