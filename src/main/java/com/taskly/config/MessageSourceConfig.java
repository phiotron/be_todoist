package com.taskly.config;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

/**
 * Chữ của server theo ngôn ngữ: messages_vi.properties (mặc định) và messages_en.properties.
 * - Bean tên "messageSource": ApplicationContext dùng nó; MessageSourceAutoConfiguration của Boot tự lùi vì đã có bean
 *   cùng tên (Boot cũng chỉ tự bật khi có messages.properties gốc — repo cố ý không có file gốc).
 * - Bean tên "localeResolver": đọc Accept-Language, chỉ nhận vi / en; thiếu header hoặc ngôn ngữ khác → vi
 *   (spec UC 11 / 8a "ngôn ngữ không còn → dùng tiếng Việt").
 */
@Configuration(proxyBeanMethods = false)
public class MessageSourceConfig {

    public static final Locale VIETNAMESE = Locale.forLanguageTag("vi");
    public static final List<Locale> SUPPORTED_LOCALES = List.of(VIETNAMESE, Locale.ENGLISH);

    @Bean
    public MessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);
        source.setDefaultLocale(VIETNAMESE);
        return source;
    }

    @Bean(name = DispatcherServlet.LOCALE_RESOLVER_BEAN_NAME)
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setSupportedLocales(SUPPORTED_LOCALES);
        resolver.setDefaultLocale(VIETNAMESE);
        return resolver;
    }
}
