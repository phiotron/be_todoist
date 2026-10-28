package com.taskly.service;

import com.taskly.repository.WebhookRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Xếp sự kiện webhook vào hàng đợi: với mỗi webhook đăng ký sự kiện này của app mà user đang có token còn hiệu lực (có
 * scope data:*), thêm một hàng webhook_deliveries kèm payload JSON. Việc gửi do WebhookDispatchService làm.
 */
@Service
public class WebhookPublisher {

    private static final Logger log = LoggerFactory.getLogger(WebhookPublisher.class);

    private final WebhookRepository webhooks;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public WebhookPublisher(WebhookRepository webhooks, JsonMapper jsonMapper, Clock clock) {
        this.webhooks = webhooks;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /** Trả số hàng đã xếp hàng. Không bao giờ ném lỗi ra ngoài (webhook không được làm hỏng request gốc). */
    @Transactional
    public int publish(UUID userId, String eventName, Object eventData) {
        return publishAll(userId, eventName, List.of(eventData));
    }

    /**
     * Nhiều đối tượng cùng một sự kiện (tasks/batch): tra webhook đích MỘT lần, mỗi đối tượng một hàng giao cho mỗi
     * webhook. Trả tổng số hàng đã xếp hàng; không ném lỗi ra ngoài.
     */
    @Transactional
    public int publishAll(UUID userId, String eventName, List<?> eventDataList) {
        try {
            List<UUID> targets = webhooks.findTargets(userId, eventName);
            Instant now = clock.instant();
            int queued = 0;
            for (Object eventData : eventDataList) {
                for (UUID webhookId : targets) {
                    UUID deliveryId = UUID.randomUUID();
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("deliveryId", deliveryId);
                    payload.put("eventName", eventName);
                    payload.put("userId", userId);
                    payload.put("eventData", eventData);
                    payload.put("triggeredAt", now.toString());
                    payload.put("version", "1");
                    webhooks.insertDelivery(deliveryId, webhookId, userId, eventName,
                            jsonMapper.writeValueAsString(payload), now);
                    queued++;
                }
            }
            return queued;
        } catch (RuntimeException e) {
            log.warn("Không xếp được sự kiện webhook {} ({})", eventName, e.getClass().getSimpleName());
            return 0;
        }
    }
}
