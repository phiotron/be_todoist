package com.taskly.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Nguồn "bây giờ" duy nhất của ứng dụng. Code cần thời gian phải inject Clock, không gọi Instant.now() trơn. */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
