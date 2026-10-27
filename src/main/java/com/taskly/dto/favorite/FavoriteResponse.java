package com.taskly.dto.favorite;

import com.taskly.entity.FavoriteTargetType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** Một mục của khối Favorites trong Browse (spec 12.2). M1 chỉ có PROJECT; M2 thêm LABEL / FILTER. */
public record FavoriteResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FavoriteTargetType targetType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID targetId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt) {
}
