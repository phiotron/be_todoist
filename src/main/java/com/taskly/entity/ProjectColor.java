package com.taskly.entity;

/**
 * 20 khoá màu (spec 10.1) dùng chung cho dự án, label, filter. Hằng viết thường CÓ CHỦ ĐÍCH: name() chính là mã
 * gửi qua API và lưu DB (@Enumerated(STRING)), nên không cần converter. Mặc định: charcoal. Inbox: null.
 * Mã hex do mobile tự chọn (theme/). Dự án KHÔNG có biểu tượng.
 */
public enum ProjectColor {
    berry_red, red, orange, yellow, olive_green, lime_green, green, mint_green, teal, sky_blue,
    light_blue, blue, grape, violet, lavender, magenta, salmon, charcoal, grey, taupe
}
