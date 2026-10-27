package com.taskly.config;

import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Nguồn "bây giờ" duy nhất của ứng dụng. Code cần thời gian phải inject Clock, không gọi Instant.now() trơn.
 * Làm tròn xuống MILI giây: khớp Postgres timestamptz (micro) nên giá trị ghi = giá trị đọc lại, và JSON Instant
 * không quá 3 chữ số thập phân (Hermes của app mobile có thể không parse nhiều hơn).
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofMillis(1));
    }
}
