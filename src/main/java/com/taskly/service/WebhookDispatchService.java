package com.taskly.service;

import com.taskly.config.WebhookProperties;
import com.taskly.repository.WebhookRepository;
import com.taskly.repository.WebhookRepository.DeliveryRow;
import com.taskly.repository.WebhookRepository.WebhookRow;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Gửi webhook từ hàng đợi (plan F5): POST payload JSON, chữ ký HMAC-SHA256 base64 trong X-Taskly-Hmac-SHA256. 2xx là
 * xong; lỗi / timeout thì thử lại sau 1 / 5 / 30 phút, tối đa 3 lần thử lại (4 request), rồi FAILED. Không theo
 * redirect. KHÔNG log URL / payload.
 */
@Service
public class WebhookDispatchService {

    public static final String SIGNATURE_HEADER = "X-Taskly-Hmac-SHA256";
    public static final String DELIVERY_HEADER = "X-Taskly-Delivery-ID";
    public static final String EVENT_HEADER = "X-Taskly-Event";
    static final int MAX_RETRIES = 3;
    static final List<Duration> RETRY_DELAYS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30));
    static final int BATCH = 50;
    /** Thời gian "thuê" hàng đang gửi: quá hạn mà chưa ghi kết quả (server chết) thì hàng được lấy lại. */
    private static final Duration LEASE = Duration.ofMinutes(2);

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatchService.class);

    private final WebhookRepository webhooks;
    private final WebhookService webhookService;
    private final WebhookUrlPolicy urlPolicy;
    private final WebhookProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final HttpClient http;

    public WebhookDispatchService(WebhookRepository webhooks, WebhookService webhookService,
                                  WebhookUrlPolicy urlPolicy, WebhookProperties properties, TransactionTemplate tx,
                                  Clock clock) {
        this.webhooks = webhooks;
        this.webhookService = webhookService;
        this.urlPolicy = urlPolicy;
        this.properties = properties;
        this.tx = tx;
        this.clock = clock;
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.connectTimeout()).build();
    }

    /** Gửi mọi hàng tới hạn. Trả số request đã gửi (để test / log). */
    public int dispatchDue() {
        Instant now = clock.instant();
        List<DeliveryRow> due = tx.execute(status -> webhooks.claimDue(now, now.plus(LEASE), BATCH));
        if (due == null || due.isEmpty()) {
            return 0;
        }
        int sent = 0;
        for (DeliveryRow delivery : due) {
            Optional<WebhookRow> webhook = webhooks.findById(delivery.webhookId());
            if (webhook.isEmpty()) {
                continue; // webhook bị xoá giữa chừng, hàng đi theo (cascade)
            }
            sent += deliver(delivery, webhook.get()) ? 1 : 0;
        }
        return sent;
    }

    /** true nếu đã gửi một request HTTP. */
    private boolean deliver(DeliveryRow delivery, WebhookRow webhook) {
        Instant now = clock.instant();
        if (!urlPolicy.isSafeToCall(webhook.url())) {
            webhooks.recordAttempt(delivery.id(), delivery.attempts(), "FAILED", now, null, now);
            log.warn("Bỏ webhook {}: địa chỉ không an toàn", delivery.id());
            return false;
        }
        Integer statusCode = null;
        boolean ok = false;
        try {
            String body = delivery.payload();
            HttpRequest request = HttpRequest.newBuilder(URI.create(webhook.url()))
                    .timeout(properties.requestTimeout())
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Taskly-Webhook/1")
                    .header(SIGNATURE_HEADER, sign(webhookService.secretOf(webhook), body))
                    .header(DELIVERY_HEADER, delivery.id().toString())
                    .header(EVENT_HEADER, delivery.eventName())
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            statusCode = response.statusCode();
            ok = statusCode >= 200 && statusCode < 300;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.info("Gửi webhook {} lỗi ({})", delivery.id(), e.getClass().getSimpleName());
        }
        int attempts = delivery.attempts() + 1;
        Instant after = clock.instant();
        if (ok) {
            webhooks.recordAttempt(delivery.id(), attempts, "SENT", after, statusCode, after);
        } else if (attempts > MAX_RETRIES) {
            webhooks.recordAttempt(delivery.id(), attempts, "FAILED", after, statusCode, after);
        } else {
            webhooks.recordAttempt(delivery.id(), attempts, "PENDING", after.plus(RETRY_DELAYS.get(attempts - 1)),
                    statusCode, after);
        }
        return true;
    }

    /** base64(HMAC-SHA256(khoá = secret UTF-8, nội dung = body UTF-8)). */
    public static String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Không ký được webhook");
        }
    }
}
