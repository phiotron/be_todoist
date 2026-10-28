package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.dto.developer.ClientSecretResponse;
import com.taskly.dto.developer.WebhookRequest;
import com.taskly.dto.developer.WebhookResponse;
import com.taskly.repository.DeveloperAppRepository;
import com.taskly.repository.WebhookRepository;
import com.taskly.repository.WebhookRepository.WebhookRow;
import com.taskly.security.OpaqueTokens;
import com.taskly.security.SecretCipher;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cấu hình webhook của một app (mỗi app một webhook, plan F5). Secret ký HMAC lưu mã hoá AES-GCM (SecretCipher, aad =
 * id webhook) vì phải dùng lại để ký; chỉ trả thô khi tạo mới hoặc xoay.
 */
@Service
@Transactional
public class WebhookService {

    static final String SECRET_PREFIX = "whs_";

    private final DeveloperAppRepository developerApps;
    private final WebhookRepository webhooks;
    private final WebhookUrlPolicy urlPolicy;
    private final SecretCipher cipher;
    private final Clock clock;

    public WebhookService(DeveloperAppRepository developerApps, WebhookRepository webhooks, WebhookUrlPolicy urlPolicy, SecretCipher cipher, Clock clock) {
        this.developerApps = developerApps;
        this.webhooks = webhooks;
        this.urlPolicy = urlPolicy;
        this.cipher = cipher;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public WebhookResponse get(UUID ownerId, UUID appId) {
        UUID id = ownedAppId(ownerId, appId);
        return webhooks.findByApp(id).map(row -> toResponse(row, null))
                .orElseThrow(() -> ApiException.notFound(ErrorMessage.WEBHOOK_NOT_FOUND));
    }

    /** Tạo hoặc thay webhook; tạo mới → trả secret một lần (created = true để controller chọn 201 / 200). */
    public Result put(UUID ownerId, UUID appId, WebhookRequest request) {
        UUID id = ownedAppId(ownerId, appId);
        String url = urlPolicy.validate(request == null ? null : request.url());
        String events = validEvents(request == null ? null : request.events());
        Instant now = clock.instant();
        return webhooks.findByApp(id).map(row -> {
            webhooks.update(row.id(), url, events, now);
            return new Result(toResponse(webhooks.findById(row.id()).orElseThrow(), null), false);
        }).orElseGet(() -> {
            UUID webhookId = UUID.randomUUID();
            String secret = newSecret();
            webhooks.insert(webhookId, id, url, events, encrypt(webhookId, secret), now);
            return new Result(toResponse(webhooks.findById(webhookId).orElseThrow(), secret), true);
        });
    }

    public ClientSecretResponse rotateSecret(UUID ownerId, UUID appId) {
        UUID id = ownedAppId(ownerId, appId);
        WebhookRow row = webhooks.findByApp(id)
                .orElseThrow(() -> ApiException.notFound(ErrorMessage.WEBHOOK_NOT_FOUND));
        String secret = newSecret();
        webhooks.updateSecret(row.id(), encrypt(row.id(), secret), clock.instant());
        return new ClientSecretResponse(secret);
    }

    public void delete(UUID ownerId, UUID appId) {
        UUID id = ownedAppId(ownerId, appId);
        if (!webhooks.deleteByApp(id)) {
            throw ApiException.notFound(ErrorMessage.WEBHOOK_NOT_FOUND);
        }
    }

    private UUID ownedAppId(UUID ownerId, UUID appId) {
        return developerApps.findOwned(appId, ownerId)
                .orElseThrow(() -> ApiException.notFound(ErrorMessage.DEVELOPER_APP_NOT_FOUND)).id();
    }

    /** Secret thô của webhook (chỉ WebhookDispatchService dùng để ký). */
    String secretOf(WebhookRow row) {
        return new String(cipher.decrypt(row.id(), row.secretEnc()), StandardCharsets.UTF_8);
    }

    WebhookResponse toResponse(WebhookRow row, String secret) {
        return new WebhookResponse(row.url(), List.of(row.events().split(",")), secret, row.createdAt(),
                row.updatedAt());
    }

    private String encrypt(UUID webhookId, String secret) {
        return cipher.encrypt(webhookId, secret.getBytes(StandardCharsets.UTF_8));
    }

    private static String newSecret() {
        return SECRET_PREFIX + OpaqueTokens.generate();
    }

    private static String validEvents(List<String> requested) {
        Set<String> events = new LinkedHashSet<>(requested == null ? List.of() : requested);
        if (events.isEmpty() || !WebhookEvents.ALL.containsAll(events)) {
            throw ApiException.badRequest(ErrorMessage.WEBHOOK_EVENTS_INVALID);
        }
        return String.join(",", Arrays.asList(events.toArray(String[]::new)));
    }

    public record Result(WebhookResponse webhook, boolean created) {
    }
}
