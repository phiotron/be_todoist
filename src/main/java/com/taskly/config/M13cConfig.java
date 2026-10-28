package com.taskly.config;

import com.taskly.common.web.WebhookEventHook;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** M13c: bật cấu hình webhook / push và gắn bộ phát sự kiện webhook vào MVC (chỉ đường dẫn REST của API). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({WebhookProperties.class, PushProperties.class})
public class M13cConfig implements WebMvcConfigurer {

    private final WebhookEventHook webhookEventHook;

    public M13cConfig(WebhookEventHook webhookEventHook) {
        this.webhookEventHook = webhookEventHook;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(webhookEventHook).addPathPatterns("/api/v1/**");
    }
}
