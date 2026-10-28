package com.taskly.config;

import com.taskly.security.InboundSignatureVerifier;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** M13a: bật EmailInboxProperties và dựng bộ kiểm chữ ký webhook inbound. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EmailInboxProperties.class)
public class EmailInboxConfig {

    @Bean
    public InboundSignatureVerifier inboundSignatureVerifier(EmailInboxProperties properties, Clock clock) {
        return new InboundSignatureVerifier(properties.webhookSecret(), properties.signatureTolerance(), clock);
    }
}
