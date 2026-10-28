package com.taskly.common.web;

import com.taskly.dto.task.AffectedTasksResponse;
import com.taskly.entity.BatchAction;
import com.taskly.security.CurrentUser;
import com.taskly.service.WebhookEvents;
import com.taskly.service.WebhookPublisher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private static final String BATCH_ATTRIBUTE = WebhookEventHook.class.getName() + ".batch";

    private record BatchContext(BatchAction action, List<UUID> taskIds) {
    }

    /**
     * TaskBatchController gọi trước khi xử lý: cho hook biết action + các task người dùng chọn (response chỉ có id đã
     * bị đổi, không có action). Lần phát lại theo Idempotency-Key không vào controller nên không có thuộc tính này.
     */
    public static void markBatch(HttpServletRequest request, BatchAction action, List<UUID> taskIds) {
        request.setAttribute(BATCH_ATTRIBUTE, new BatchContext(action, List.copyOf(taskIds)));
    }

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
            if (request.getAttribute(BATCH_ATTRIBUTE) instanceof BatchContext batch
                    && request.getAttribute(BODY_ATTRIBUTE) instanceof AffectedTasksResponse affected
                    && response.getStatus() >= 200 && response.getStatus() < 300) {
                publishBatch(CurrentUser.id(jwt), batch, affected);
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

    /**
     * tasks/batch: mỗi task NGƯỜI DÙNG CHỌN (và thật sự bị đổi) phát một sự kiện như thao tác đơn lẻ; con cháu bị kéo
     * theo (đóng / xoá cha) không phát riêng — giống đóng / xoá cha ở endpoint đơn. eventData = {id} (không có body
     * từng task; task đã xoá cũng chỉ còn id).
     */
    private void publishBatch(UUID userId, BatchContext batch, AffectedTasksResponse affected) {
        Set<UUID> changed = new HashSet<>(affected.affectedTaskIds());
        List<Map<String, Object>> data = new ArrayList<>();
        for (UUID taskId : batch.taskIds()) {
            if (changed.contains(taskId)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", taskId.toString());
                data.add(item);
            }
        }
        if (!data.isEmpty()) {
            publisher.publishAll(userId, WebhookEvents.batchEvent(batch.action()), data);
        }
    }
}
