package com.taskly.support;

import com.taskly.integration.OutgoingMail;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lấy token đặt lại mật khẩu từ liên kết taskly://reset-password/<token> trong thư (test). */
public final class PasswordResetMails {

    public static final Pattern LINK = Pattern.compile("taskly://reset-password/([A-Za-z0-9_-]{43})");

    private PasswordResetMails() {
    }

    public static String tokenFrom(OutgoingMail mail) {
        Matcher matcher = LINK.matcher(mail.body());
        if (!matcher.find()) {
            throw new AssertionError("Thư không chứa liên kết taskly://reset-password/<token 43 ký tự>");
        }
        return matcher.group(1);
    }
}
