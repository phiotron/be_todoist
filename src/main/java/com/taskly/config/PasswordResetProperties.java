package com.taskly.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình app.password-reset.* (application.yml):
 * - linkBase: tiền tố liên kết trong thư, token nối ngay sau (taskly://reset-password/);
 * - tokenTtl: thời gian sống của token (30 phút, spec mục 2).
 */
@ConfigurationProperties(prefix = "app.password-reset")
public record PasswordResetProperties(String linkBase, Duration tokenTtl) {
}
