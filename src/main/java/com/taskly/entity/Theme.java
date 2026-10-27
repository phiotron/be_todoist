package com.taskly.entity;

/**
 * Theme màu của app theo tài khoản (users.theme, spec bản 3 mục 9.3 / 10) — mỗi theme có bản sáng + tối, chọn bằng
 * Appearance. TASKLY là theme mặc định (màu thương hiệu riêng của Taskly, không phải đỏ; mã màu do plan mobile M3 chốt).
 * Bản 2 dùng LIGHT | DARK | SYSTEM — nay thuộc Appearance (SYSTEM → AUTO).
 */
public enum Theme {
    TASKLY,
    MOONSTONE,
    TANGERINE,
    KALE,
    BLUEBERRY,
    LAVENDER,
    RASPBERRY
}
