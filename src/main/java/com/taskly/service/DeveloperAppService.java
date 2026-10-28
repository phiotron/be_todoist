package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.dto.developer.ClientSecretResponse;
import com.taskly.dto.developer.CreateDeveloperAppRequest;
import com.taskly.dto.developer.DeveloperAppResponse;
import com.taskly.dto.developer.UpdateDeveloperAppRequest;
import com.taskly.dto.developer.WebhookResponse;
import com.taskly.repository.DeveloperAppRepository;
import com.taskly.repository.DeveloperAppRepository.AppRow;
import com.taskly.repository.WebhookRepository;
import com.taskly.security.OpaqueTokens;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * App của nhà phát triển (spec 3.2 M13c, plan F1): client id công khai + client secret chỉ lưu SHA-256, hiện đúng một
 * lần. Chỉ chủ app đọc / sửa / xoá — app của người khác trả 404 như không tồn tại.
 */
@Service
@Transactional
public class DeveloperAppService {

    static final int NAME_MAX = 100;
    static final String CLIENT_ID_PREFIX = "tkc_";
    static final String CLIENT_SECRET_PREFIX = "tks_";
    /** client_id: tiền tố + 24 ký tự ngẫu nhiên (đủ khó đoán; không phải bí mật). */
    private static final int CLIENT_ID_RANDOM_CHARS = 24;

    private final DeveloperAppRepository apps;
    private final WebhookRepository webhooks;
    private final WebhookService webhookService;
    private final Clock clock;

    public DeveloperAppService(DeveloperAppRepository apps, WebhookRepository webhooks, WebhookService webhookService,
                               Clock clock) {
        this.apps = apps;
        this.webhooks = webhooks;
        this.webhookService = webhookService;
        this.clock = clock;
    }

    public DeveloperAppResponse create(UUID ownerId, CreateDeveloperAppRequest request) {
        String name = validName(request == null ? null : request.name());
        String redirectUri = RedirectUris.validate(request == null ? null : request.redirectUri());
        UUID id = UUID.randomUUID();
        String clientId = CLIENT_ID_PREFIX + OpaqueTokens.generate().substring(0, CLIENT_ID_RANDOM_CHARS);
        String secret = CLIENT_SECRET_PREFIX + OpaqueTokens.generate();
        Instant now = clock.instant();
        apps.insert(id, ownerId, name, clientId, OpaqueTokens.sha256Hex(secret), redirectUri, now);
        return new DeveloperAppResponse(id, name, clientId, secret, redirectUri, null, now);
    }

    @Transactional(readOnly = true)
    public List<DeveloperAppResponse> list(UUID ownerId) {
        return apps.listByOwner(ownerId).stream().map(this::toResponse).toList();
    }

    public DeveloperAppResponse update(UUID ownerId, UUID appId, UpdateDeveloperAppRequest request) {
        AppRow app = owned(ownerId, appId);
        String name = request != null && request.name() != null ? validName(request.name()) : app.name();
        String redirectUri = request != null && request.redirectUri() != null
                ? RedirectUris.validate(request.redirectUri()) : app.redirectUri();
        apps.update(app.id(), name, redirectUri, clock.instant());
        return toResponse(owned(ownerId, appId));
    }

    /** Xoay secret: secret cũ hết hiệu lực ngay. */
    public ClientSecretResponse rotateSecret(UUID ownerId, UUID appId) {
        AppRow app = owned(ownerId, appId);
        String secret = CLIENT_SECRET_PREFIX + OpaqueTokens.generate();
        apps.updateSecret(app.id(), OpaqueTokens.sha256Hex(secret), clock.instant());
        return new ClientSecretResponse(secret);
    }

    /** Xoá app: token, mã ủy quyền, webhook, hàng đợi gửi đi theo (ON DELETE CASCADE). */
    public void delete(UUID ownerId, UUID appId) {
        apps.delete(owned(ownerId, appId).id());
    }

    /** App thuộc chủ này; không thì 404. Dùng chung cho WebhookService. */
    AppRow owned(UUID ownerId, UUID appId) {
        return apps.findOwned(appId, ownerId).orElseThrow(() -> ApiException.notFound(ErrorMessage.DEVELOPER_APP_NOT_FOUND));
    }

    private DeveloperAppResponse toResponse(AppRow app) {
        WebhookResponse webhook = webhooks.findByApp(app.id()).map(row -> webhookService.toResponse(row, null))
                .orElse(null);
        return new DeveloperAppResponse(app.id(), app.name(), app.clientId(), null, app.redirectUri(), webhook,
                app.createdAt());
    }

    private static String validName(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty() || name.codePointCount(0, name.length()) > NAME_MAX) {
            throw ApiException.badRequest(ErrorMessage.DEVELOPER_APP_NAME_SIZE);
        }
        return name;
    }
}
