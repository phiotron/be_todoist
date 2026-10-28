package com.taskly.config;

import java.time.Duration;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình taskly.email-inbox.* (M13a, spec mục 15 — người dùng tự cấu hình tên miền + dịch vụ nhận thư):
 * - domain: tên miền của địa chỉ nhận thư (biến EMAIL_INBOX_DOMAIN), vd "in.example.com" (MX trỏ về dịch vụ inbound);
 * - webhookSecret: bí mật HMAC dùng chung với adapter của dịch vụ inbound (biến EMAIL_INBOUND_WEBHOOK_SECRET); để trống =
 *   webhook từ chối mọi yêu cầu (server vẫn chạy);
 * - signatureTolerance: lệch giờ tối đa của X-Taskly-Timestamp (mặc định 5 phút);
 * - maxBodyBytes: kích thước body tối đa của webhook (mặc định 2 MiB).
 */
@ConfigurationProperties(prefix = "taskly.email-inbox")
public record EmailInboxProperties(String domain, String webhookSecret, Duration signatureTolerance,
                                   Integer maxBodyBytes) {

    public EmailInboxProperties {
        domain = domain == null || domain.isBlank() ? "inbox.taskly.local" : domain.strip().toLowerCase(Locale.ROOT);
        webhookSecret = webhookSecret == null ? "" : webhookSecret;
        signatureTolerance = signatureTolerance == null ? Duration.ofMinutes(5) : signatureTolerance;
        maxBodyBytes = maxBodyBytes == null ? 2 * 1024 * 1024 : maxBodyBytes;
    }

    /** Không để lộ bí mật khi record bị in ra log. */
    @Override
    public String toString() {
        return "EmailInboxProperties[domain=" + domain + ", webhookSecret=***, signatureTolerance=" + signatureTolerance
                + ", maxBodyBytes=" + maxBodyBytes + "]";
    }
}
