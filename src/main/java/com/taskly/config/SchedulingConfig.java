package com.taskly.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Bật @Scheduled — job nền duy nhất của Mức 1 là IdempotencyCleanupJob (spec 8.2.10). Không có job dọn thùng rác
 * (bản 3 xoá vĩnh viễn). Test đặt app.jobs.enabled=false để job không chạy ngầm.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
