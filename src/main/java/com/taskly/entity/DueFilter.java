package com.taskly.entity;

/** Display: lọc theo hạn (filterDue). Tuần bắt đầu Thứ Hai tới khi M3 có cài đặt start_day. */
public enum DueFilter {
    TODAY, THIS_WEEK, NEXT_7_DAYS, THIS_MONTH, NEXT_30_DAYS, NO_DATE, OVERDUE
}
