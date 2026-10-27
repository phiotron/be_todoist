package com.taskly.security;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Bean GoogleIdTokenVerifier chạy thật (JWKS của Google). Test thay bằng bản @Primary tin khoá test. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GoogleProperties.class)
public class GoogleAuthConfig {

    private static final Logger log = LoggerFactory.getLogger(GoogleAuthConfig.class);

    @Bean
    GoogleIdTokenVerifier googleIdTokenVerifier(GoogleProperties properties, Clock clock) {
        if (properties.clientIds().isEmpty()) {
            log.warn("GOOGLE_CLIENT_IDS chưa được cấu hình — mọi yêu cầu đăng nhập Google sẽ bị từ chối "
                    + "(xem .env.example)");
        }
        return GoogleIdTokenVerifier.withJwkSetUri(properties, clock);
    }
}
