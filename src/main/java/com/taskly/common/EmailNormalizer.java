package com.taskly.common;

import java.util.Locale;

/** Chuẩn hoá email trước khi lưu/so sánh: bỏ khoảng trắng hai đầu và chuyển chữ thường (Locale.ROOT). */
public final class EmailNormalizer {

    private EmailNormalizer() {
    }

    public static String normalize(String email) {
        return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }
}
