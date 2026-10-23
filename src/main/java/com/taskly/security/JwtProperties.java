package com.taskly.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Cấu hình app.jwt.* trong application.yml (secret lấy từ biến JWT_SECRET trong .env). */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String secret, String issuer, Duration accessTtl, Duration refreshTtl) {

    /** Không để lộ secret khi record bị in ra log. */
    @Override
    public String toString() {
        return "JwtProperties[secret=***, issuer=" + issuer + ", accessTtl=" + accessTtl
                + ", refreshTtl=" + refreshTtl + "]";
    }
}
