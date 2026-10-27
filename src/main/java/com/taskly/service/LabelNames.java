package com.taskly.service;

import com.taskly.common.error.ErrorMessage;
import com.taskly.common.error.ApiException;
import java.text.Normalizer;
import java.util.regex.Pattern;

/** Chuẩn hoá + kiểm tên label (spec 5.9): NFC, cắt khoảng trắng hai đầu, 1–60 ký tự, không khoảng trắng / ký tự cấm. */
public final class LabelNames {

    public static final int MAX_LENGTH = 60;

    private static final Pattern FORBIDDEN = Pattern.compile("[\\s@%#/,()|&!\\\\*]", Pattern.UNICODE_CHARACTER_CLASS);

    private LabelNames() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            throw ApiException.badRequest(ErrorMessage.LABEL_NAME_SIZE);
        }
        String name = Normalizer.normalize(raw, Normalizer.Form.NFC).strip();
        int length = name.codePointCount(0, name.length());
        if (length < 1 || length > MAX_LENGTH) {
            throw ApiException.badRequest(ErrorMessage.LABEL_NAME_SIZE);
        }
        if (FORBIDDEN.matcher(name).find()) {
            throw ApiException.badRequest(ErrorMessage.LABEL_NAME_CHARS);
        }
        return name;
    }
}
