package com.taskly.entity;

/** Display: nhóm (spec 12.7). NONE ở view dự án = nhóm theo section. */
public enum GroupBy {
    NONE, DATE, DEADLINE, PRIORITY, PROJECT, LABEL,
    /** M7: ngày tạo (created_at theo múi giờ tài khoản), ngày mới trước. */
    ADDED
}
