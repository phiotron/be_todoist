package com.taskly.security;

import com.taskly.integration.facebook.FacebookClient;
import com.taskly.integration.facebook.GraphFacebookClient;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Bean chạy thật cho Apple (JWKS) và Facebook (Graph API) — M13b. Test thay bằng bản @Primary giả. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AppleProperties.class, FacebookProperties.class})
public class SocialAuthConfig {

    private static final Logger log = LoggerFactory.getLogger(SocialAuthConfig.class);

    @Bean
    AppleIdTokenVerifier appleIdTokenVerifier(AppleProperties properties, Clock clock) {
        if (properties.clientIds().isEmpty()) {
            log.warn("APPLE_CLIENT_IDS chưa được cấu hình — mọi yêu cầu đăng nhập Apple sẽ bị từ chối (xem .env.example)");
        }
        return NimbusAppleIdTokenVerifier.withJwkSetUri(properties, clock);
    }

    @Bean
    FacebookClient facebookClient(FacebookProperties properties) {
        if (!properties.configured()) {
            log.warn("FACEBOOK_APP_ID / FACEBOOK_APP_SECRET chưa được cấu hình — đăng nhập Facebook trả 503 (xem .env.example)");
        }
        return new GraphFacebookClient(properties);
    }
}
