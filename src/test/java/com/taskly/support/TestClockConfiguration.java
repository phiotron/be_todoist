package com.taskly.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Đăng ký MutableClock là Clock @Primary: mọi bean inject Clock trong test nhận bản dời được này. */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfiguration {

    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock();
    }
}
