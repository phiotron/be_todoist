package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.notification.NotificationPrefsResponse;
import com.taskly.dto.notification.UpdateNotificationPrefsRequest;
import com.taskly.security.CurrentUser;
import com.taskly.service.NotificationPrefsService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Cài đặt thông báo từ server: email Morning / Evening / Subscribed, nhắc qua push / email (M13c, F7). */
@RestController
@RequestMapping("/api/v1/me/notification-prefs")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class NotificationPrefsController {

    private final NotificationPrefsService prefs;

    public NotificationPrefsController(NotificationPrefsService prefs) {
        this.prefs = prefs;
    }

    @GetMapping
    public NotificationPrefsResponse get(@AuthenticationPrincipal Jwt jwt) {
        return prefs.get(CurrentUser.id(jwt));
    }

    /** Từng phần: trường vắng hoặc null giữ nguyên. */
    @PatchMapping
    public NotificationPrefsResponse update(@AuthenticationPrincipal Jwt jwt,
                                            @RequestBody UpdateNotificationPrefsRequest request) {
        return prefs.update(CurrentUser.id(jwt), request);
    }
}
