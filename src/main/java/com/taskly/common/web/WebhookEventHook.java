package com.taskly.common.web;

import com.taskly.security.CurrentUser;
import com.taskly.service.WebhookEvents;
import com.taskly.service.WebhookPublisher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Phát sự kiện webhook sau mỗi request REST thành công (plan M13c, F5). Hai nửa trong một lớp:
 * - ResponseBodyAdvice cất body trả về vào thuộc tính request (sự kiện kèm dữ liệu đối tượng);
 * - HandlerInterceptor.afterCompletion (sau khi service đã commit) tra WebhookEvents rồi xếp hàng qua WebhookPublisher;
 *   đối tượng không có body (xoá) thì eventData = {id}. Mọi lỗi được nuốt: webhook không làm hỏng request gốc.
 */
@RestControllerAdvice
public class WebhookEventHook implements ResponseBodyAdvice<Object>, HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WebhookEventHook.class);
    private static final String BODY_ATTRIBUTE = WebhookEventHook.class.getName() + ".body";

    private final WebhookPublisher publisher;

    public WebhookEventHook(WebhookPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body != null && request instanceof ServletServerHttpRequest servletRequest) {
            servletRequest.getServletRequest().setAttribute(BODY_ATTRIBUTE, body);
        }
        return body;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        if (ex != null || request.getMethod().equals("GET")) {
            return;
        }
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
                return;
            }
            String pattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            Optional<String> event = WebhookEvents.resolve(request.getMethod(), pattern, response.getStatus());
            if (event.isEmpty()) {
                return;
            }
            Object body = request.getAttribute(BODY_ATTRIBUTE);
            if (body == null) {
                Map<String, String> variables =
                        (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("id", variables == null ? null : variables.get(WebhookEvents.idVariable(event.get())));
                body = data;
            }
            UUID userId = CurrentUser.id(jwt);
            publisher.publish(userId, event.get(), body);
        } catch (RuntimeException e) {
            log.warn("Không phát được sự kiện webhook ({})", e.getClass().getSimpleName());
        }
    }
}
