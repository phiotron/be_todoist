package com.taskly.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * 401 của Spring Security (thiếu/sai/hết hạn JWT) → chuyển cho GlobalExceptionHandler
 * để body là ProblemDetail giống mọi lỗi khác, không tự viết JSON bằng tay.
 */
@Component
public class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final HandlerExceptionResolver handlerExceptionResolver;

    public ProblemDetailAuthenticationEntryPoint(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver) {
        this.handlerExceptionResolver = handlerExceptionResolver;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        ModelAndView handled = handlerExceptionResolver.resolveException(request, response, null, authException);
        if (handled == null) {
            // Không có resolver nào xử lý: tuyệt đối không để 200 rỗng.
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }
}
