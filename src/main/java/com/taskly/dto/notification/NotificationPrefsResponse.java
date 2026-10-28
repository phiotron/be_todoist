package com.taskly.dto.notification;

import io.swagger.v3.oas.annotations.media.Schema;

public record NotificationPrefsResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean reminderPush,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean reminderEmail,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean morningOverview,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean eveningReview,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean subscribedEmails) {
}
