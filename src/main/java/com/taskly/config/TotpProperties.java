package com.taskly.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình taskly.totp.* (spec mục 5.9):
 * - encryptionKey: base64 của 32 byte, khoá AES mã hoá secret 2FA (biến TOTP_ENCRYPTION_KEY trong .env);
 * - issuer: tên hiện trong app authenticator ("Taskly").
 */
@ConfigurationProperties(prefix = "taskly.totp")
public record TotpProperties(String encryptionKey, String issuer) {

    /** Không để lộ khoá khi record bị in ra log. */
    @Override
    public String toString() {
        return "TotpProperties[encryptionKey=***, issuer=" + issuer + "]";
    }
}
