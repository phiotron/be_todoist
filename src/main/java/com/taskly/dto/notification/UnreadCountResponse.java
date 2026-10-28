package com.taskly.dto.notification;

import io.swagger.v3.oas.annotations.media.Schema;

/** GET notifications/unread-count (M8b) — chấm số trên 🔔 và tab Chưa đọc. */
public record UnreadCountResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) long count) {
}
