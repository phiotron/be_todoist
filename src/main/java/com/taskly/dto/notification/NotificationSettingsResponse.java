package com.taskly.dto.notification;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** GET / PUT me/notification-settings (M8b): đủ mọi cặp (scope, event), mặc định bật. */
public record NotificationSettingsResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<NotificationSettingItem> items) {
}
