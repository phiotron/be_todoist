package com.taskly.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình push (taskly.push.*). provider = fake (mặc định, không gọi mạng) hoặc fcm (FCM HTTP v1: cần projectId và
 * file service account JSON do người dùng tự tạo trong Firebase). fcmBaseUrl chỉ để test trỏ về server giả.
 * toString che giá trị (đường dẫn file khoá).
 */
@ConfigurationProperties("taskly.push")
public record PushProperties(String provider, String projectId, String serviceAccountFile, String fcmBaseUrl,
                             Duration timeout) {

    public PushProperties {
        provider = provider == null || provider.isBlank() ? "fake" : provider.strip();
        fcmBaseUrl = fcmBaseUrl == null || fcmBaseUrl.isBlank() ? "https://fcm.googleapis.com" : fcmBaseUrl.strip();
        timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
    }

    @Override
    public String toString() {
        return "PushProperties[provider=" + provider + ", projectId=***, serviceAccountFile=***]";
    }
}
