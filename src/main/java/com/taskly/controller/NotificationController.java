package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.common.Page;
import com.taskly.dto.notification.NotificationResponse;
import com.taskly.dto.notification.NotificationSettingsResponse;
import com.taskly.dto.notification.UnreadCountResponse;
import com.taskly.dto.notification.UpdateNotificationSettingsRequest;
import com.taskly.security.CurrentUser;
import com.taskly.service.NotificationService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Hộp thông báo All / Unread + cài đặt Project / Workspace notifications (M8b, plan F5). */
@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** Mới trước; unread=true chỉ chưa đọc. Phân trang con trỏ (limit 1–200, mặc định 50). */
    @GetMapping("/notifications")
    public Page<NotificationResponse> list(@AuthenticationPrincipal Jwt jwt,
                                           @RequestParam(defaultValue = "false") boolean unread,
                                           @RequestParam(required = false) String cursor,
                                           @RequestParam(required = false) Integer limit) {
        return notificationService.list(CurrentUser.id(jwt), unread, cursor, limit);
    }

    @GetMapping("/notifications/unread-count")
    public UnreadCountResponse unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return notificationService.unreadCount(CurrentUser.id(jwt));
    }

    @PostMapping("/notifications/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(@AuthenticationPrincipal Jwt jwt) {
        notificationService.markAllRead(CurrentUser.id(jwt));
    }

    @PostMapping("/notifications/{notificationId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID notificationId) {
        notificationService.markRead(CurrentUser.id(jwt), notificationId);
    }

    @GetMapping("/me/notification-settings")
    public NotificationSettingsResponse settings(@AuthenticationPrincipal Jwt jwt) {
        return notificationService.settings(CurrentUser.id(jwt));
    }

    @PutMapping("/me/notification-settings")
    public NotificationSettingsResponse updateSettings(@AuthenticationPrincipal Jwt jwt,
                                                       @Valid @RequestBody UpdateNotificationSettingsRequest request) {
        return notificationService.updateSettings(CurrentUser.id(jwt), request);
    }
}
