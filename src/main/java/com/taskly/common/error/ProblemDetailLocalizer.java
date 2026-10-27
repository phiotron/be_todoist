package com.taskly.common.error;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.MessageSource;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Dịch mọi ProblemDetail ngay trước khi ghi body — kể cả lỗi 401 đi qua ProblemDetailAuthenticationEntryPoint
 * (ExceptionHandlerExceptionResolver cũng áp ResponseBodyAdvice):
 * - title: luôn lấy theo mã HTTP (ProblemTexts.titleKey);
 * - detail và errors[].message: là key có trong messages_*.properties thì thay bằng câu đã dịch, không thì giữ nguyên;
 *   errors sắp theo field rồi message (sau khi dịch) cho ổn định.
 * Ngôn ngữ lấy bằng LocaleResolver (Accept-Language vi | en, mặc định vi) — không dựa vào LocaleContextHolder vì lỗi
 * 401 phát sinh trong filter Spring Security, ngoài DispatcherServlet. Không đụng thuộc tính khác (vd requestId).
 */
@RestControllerAdvice
public class ProblemDetailLocalizer implements ResponseBodyAdvice<Object> {

    private final MessageSource messageSource;
    private final LocaleResolver localeResolver;

    public ProblemDetailLocalizer(MessageSource messageSource, LocaleResolver localeResolver) {
        this.messageSource = messageSource;
        this.localeResolver = localeResolver;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof ProblemDetail problem && request instanceof ServletServerHttpRequest servletRequest) {
            localize(problem, localeResolver.resolveLocale(servletRequest.getServletRequest()));
        }
        return body;
    }

    void localize(ProblemDetail problem, Locale locale) {
        problem.setTitle(text(ProblemTexts.titleKey(problem.getStatus()), locale));
        if (problem.getDetail() != null) {
            problem.setDetail(text(problem.getDetail(), locale));
        }
        Map<String, Object> properties = problem.getProperties();
        if (properties != null && properties.get("errors") instanceof List<?> errors
                && errors.stream().allMatch(FieldErrorItem.class::isInstance)) {
            List<FieldErrorItem> localized = errors.stream()
                    .map(FieldErrorItem.class::cast)
                    .map(error -> new FieldErrorItem(error.field(), text(error.message(), locale)))
                    .sorted(Comparator.comparing(FieldErrorItem::field).thenComparing(FieldErrorItem::message))
                    .toList();
            problem.setProperty("errors", localized);
        }
    }

    private String text(String keyOrText, Locale locale) {
        return messageSource.getMessage(keyOrText, null, keyOrText, locale);
    }
}
