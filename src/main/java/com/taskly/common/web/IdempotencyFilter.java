package com.taskly.common.web;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.security.CurrentUser;
import com.taskly.security.OpaqueTokens;
import com.taskly.service.IdempotencyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Header Idempotency-Key cho các POST tạo / sao chép / batch (spec 12.11). KHÔNG phải bean (tránh Boot tự đăng ký
 * thành servlet filter chạy trước Security) — SecurityConfig tạo và gắn sau AuthorizationFilter để biết user.
 * Luồng: begin() chèn hàng khoá (commit ngay) → chạy request → 2xx thì complete() ghi kết quả, còn lại abandon()
 * xoá hàng (spec 12.11: lỗi 4xx / 5xx / ngoại lệ không được lưu).
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAY_HEADER = "Idempotent-Replay";
    static final int MAX_KEY_LENGTH = 64;

    /** POST tạo / sao chép / batch có chống gửi trùng (spec 12.11, 12.5). M2 thêm "/api/v1/labels",
     *  "/api/v1/filters"; M4 thêm "/api/v1/tasks/{taskId}/reminders". */
    private static final List<PathPattern> COVERED = List.of(
            "/api/v1/projects",
            "/api/v1/projects/{projectId}/sections",
            "/api/v1/tasks",
            "/api/v1/tasks/{taskId}/duplicate",
            "/api/v1/tasks/batch").stream().map(PathPatternParser.defaultInstance::parse).toList();

    /** Đường dẫn (requestURI) có nằm trong danh sách POST được bảo vệ không — test đơn vị gọi trực tiếp. */
    public static boolean covers(String requestUri) {
        PathContainer path = PathContainer.parsePath(requestUri);
        return COVERED.stream().anyMatch(pattern -> pattern.matches(path));
    }

    private final IdempotencyService idempotencyService;
    private final HandlerExceptionResolver handlerExceptionResolver;

    public IdempotencyFilter(IdempotencyService idempotencyService, HandlerExceptionResolver handlerExceptionResolver) {
        this.idempotencyService = idempotencyService;
        this.handlerExceptionResolver = handlerExceptionResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod()) || request.getHeader(HEADER) == null) {
            return true;
        }
        return !covers(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (key.isEmpty() || key.length() > MAX_KEY_LENGTH) {
            fail(request, response, ApiException.badRequest(ErrorMessage.IDEMPOTENCY_KEY_INVALID));
            return;
        }
        UUID userId = currentUserId();
        if (userId == null) {
            chain.doFilter(request, response);
            return;
        }
        CachedBodyRequest cached = new CachedBodyRequest(request);
        String hash = OpaqueTokens.sha256Hex(new String(cached.body(), StandardCharsets.UTF_8));
        IdempotencyService.Outcome outcome;
        try {
            outcome = idempotencyService.begin(userId, key, request.getMethod(), request.getRequestURI(), hash);
        } catch (ApiException e) {
            // 422 idempotency-mismatch / 409 idempotency-in-progress (kể cả bản thua đua unique — xử lý trong begin())
            fail(request, response, e);
            return;
        }
        if (outcome instanceof IdempotencyService.Replay replay) {
            response.setStatus(replay.status());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader(REPLAY_HEADER, "true");
            response.getOutputStream().write(replay.body().getBytes(StandardCharsets.UTF_8));
            return;
        }
        UUID recordId = ((IdempotencyService.Proceed) outcome).recordId();
        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        boolean stored = false;
        try {
            chain.doFilter(cached, wrapped);
            int status = wrapped.getStatus();
            if (status >= 200 && status < 300) {
                idempotencyService.complete(recordId, status,
                        new String(wrapped.getContentAsByteArray(), StandardCharsets.UTF_8));
                stored = true;
            }
        } finally {
            if (!stored) {
                idempotencyService.abandon(recordId);
            }
            wrapped.copyBodyToResponse();
        }
    }

    private void fail(HttpServletRequest request, HttpServletResponse response, ApiException error) {
        handlerExceptionResolver.resolveException(request, response, null, error);
    }

    private static UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            try {
                return CurrentUser.id(jwtAuthentication.getToken());
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }
}
