package com.taskly.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** Đọc messages_vi / messages_en.properties (UTF-8) từ classpath để test so key giữa hai ngôn ngữ. */
public final class MessageBundles {

    public static final String VI = "messages_vi.properties";
    public static final String EN = "messages_en.properties";

    private MessageBundles() {
    }

    public static Properties load(String resource) {
        try (InputStream in = MessageBundles.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Không thấy " + resource + " trên classpath");
            }
            Properties properties = new Properties();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return properties;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
