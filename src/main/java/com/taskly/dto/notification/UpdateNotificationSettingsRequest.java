package com.taskly.dto.notification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** PUT me/notification-settings (M8b): chỉ các cặp gửi lên bị đổi; cặp vắng giữ nguyên. */
public record UpdateNotificationSettingsRequest(
        @NotNull(message = "{validation.notification-settings.items.required}")
        @Size(max = 50, message = "{validation.invalid}")
        List<@Valid @NotNull(message = "{validation.invalid}") NotificationSettingItem> items) {
}
