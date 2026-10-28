package com.taskly.dto.notification;

import com.taskly.entity.NotificationEvent;
import com.taskly.entity.NotificationScope;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** Một công tắc trong cài đặt Project / Workspace notifications (M8b). scope phải khớp event (else 400). */
public record NotificationSettingItem(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull(message = "{validation.invalid}")
        NotificationScope scope,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull(message = "{validation.invalid}")
        NotificationEvent event,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull(message = "{validation.invalid}")
        Boolean enabled) {
}
