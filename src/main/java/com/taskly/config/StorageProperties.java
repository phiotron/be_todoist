package com.taskly.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Cấu hình taskly.storage.* (M8b): root = thư mục lưu file (STORAGE_DIR), downloadLinkTtl = tuổi link tải ký HMAC,
 * unattachedTtl = file tải lên chưa gắn comment quá hạn thì bị dọn.
 */
@ConfigurationProperties(prefix = "taskly.storage")
public record StorageProperties(String root, Duration downloadLinkTtl, Duration unattachedTtl) {

    public StorageProperties {
        if (root == null || root.isBlank()) {
            root = "./data/files";
        }
        if (downloadLinkTtl == null) {
            downloadLinkTtl = Duration.ofMinutes(5);
        }
        if (unattachedTtl == null) {
            unattachedTtl = Duration.ofHours(24);
        }
    }

    /** Bật StorageProperties. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(StorageProperties.class)
    public static class Config {
    }
}
