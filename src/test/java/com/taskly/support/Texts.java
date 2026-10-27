package com.taskly.support;

import com.taskly.common.error.ErrorMessage;
import java.util.Properties;

/**
 * Câu tiếng Việt (ngôn ngữ mặc định) của một ErrorMessage — thay cho hằng Texts.vi(ErrorMessage.X) mà test M1 / M2 dùng để so
 * $.detail / errors[].message trước khi có i18n.
 */
public final class Texts {

    private static final Properties VI = MessageBundles.load(MessageBundles.VI);

    private Texts() {
    }

    public static String vi(ErrorMessage message) {
        String text = VI.getProperty(message.key());
        if (text == null) {
            throw new IllegalStateException("Thiếu key " + message.key() + " trong messages_vi.properties");
        }
        return text;
    }
}
