package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.developer.ClientSecretResponse;
import com.taskly.dto.developer.CreateDeveloperAppRequest;
import com.taskly.dto.developer.DeveloperAppResponse;
import com.taskly.dto.developer.UpdateDeveloperAppRequest;
import com.taskly.dto.developer.WebhookRequest;
import com.taskly.dto.developer.WebhookResponse;
import com.taskly.security.CurrentUser;
import com.taskly.service.DeveloperAppService;
import com.taskly.service.WebhookService;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * App của nhà phát triển + webhook của app (M13c, F1 / F5). Chỉ token của app Taskly dùng được (token OAuth → 403
 * insufficient-scope vì không nằm trong danh sách endpoint mở cho OAuth).
 */
@RestController
@RequestMapping("/api/v1/developer/apps")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class DeveloperAppController {

    private final DeveloperAppService apps;
    private final WebhookService webhooks;

    public DeveloperAppController(DeveloperAppService apps, WebhookService webhooks) {
        this.apps = apps;
        this.webhooks = webhooks;
    }

    @GetMapping
    public List<DeveloperAppResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return apps.list(CurrentUser.id(jwt));
    }

    /** 201; `clientSecret` chỉ hiện ở response này. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DeveloperAppResponse create(@AuthenticationPrincipal Jwt jwt, @RequestBody CreateDeveloperAppRequest request) {
        return apps.create(CurrentUser.id(jwt), request);
    }

    @PatchMapping("/{appId}")
    public DeveloperAppResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID appId,
                                       @RequestBody UpdateDeveloperAppRequest request) {
        return apps.update(CurrentUser.id(jwt), appId, request);
    }

    @DeleteMapping("/{appId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID appId) {
        apps.delete(CurrentUser.id(jwt), appId);
    }

    /** Xoay client secret: secret cũ hết hiệu lực ngay. */
    @PostMapping("/{appId}/secret")
    public ClientSecretResponse rotateSecret(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID appId) {
        return apps.rotateSecret(CurrentUser.id(jwt), appId);
    }

    @GetMapping("/{appId}/webhook")
    public WebhookResponse getWebhook(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID appId) {
        return webhooks.get(CurrentUser.id(jwt), appId);
    }

    /** Tạo (201, kèm `secret` một lần) hoặc thay (200) webhook của app. */
    @PutMapping("/{appId}/webhook")
    @ApiResponse(responseCode = "201", description = "Đã tạo webhook, kèm secret ký HMAC (chỉ hiện một lần)")
    @ApiResponse(responseCode = "200", description = "Đã cập nhật webhook (secret giữ nguyên, không trả lại)")
    public ResponseEntity<WebhookResponse> putWebhook(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID appId,
                                                      @RequestBody WebhookRequest request) {
        WebhookService.Result result = webhooks.put(CurrentUser.id(jwt), appId, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.webhook());
    }

    @PostMapping("/{appId}/webhook/secret")
    public ClientSecretResponse rotateWebhookSecret(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID appId) {
        return webhooks.rotateSecret(CurrentUser.id(jwt), appId);
    }

    @DeleteMapping("/{appId}/webhook")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteWebhook(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID appId) {
        webhooks.delete(CurrentUser.id(jwt), appId);
    }
}
