package com.taskly.config;

import com.taskly.security.SecretCipher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Bật TotpProperties và dựng SecretCipher — khoá sai / thiếu thì ứng dụng dừng ngay lúc khởi động. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TotpProperties.class)
public class TotpConfig {

    @Bean
    public SecretCipher secretCipher(TotpProperties properties) {
        return new SecretCipher(properties.encryptionKey());
    }
}
