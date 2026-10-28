package com.taskly.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình webhook (taskly.webhooks.*). allowInsecure = chỉ dev / test: cho http và địa chỉ nội bộ (loopback...);
 * mặc định false: https bắt buộc và chặn địa chỉ nội bộ (chống SSRF).
 */
@ConfigurationProperties("taskly.webhooks")
public record WebhookProperties(boolean allowInsecure, Duration connectTimeout, Duration requestTimeout) {

    public WebhookProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(10) : requestTimeout;
    }
}
