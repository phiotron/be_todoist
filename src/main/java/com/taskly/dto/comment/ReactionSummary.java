package com.taskly.dto.comment;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import java.util.List;

/** Một emoji trên comment: số người + người đang xem đã thả chưa (M8b). */
public record ReactionSummary(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String emoji,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int count,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean reactedByMe,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<UUID> userIds) {
}
