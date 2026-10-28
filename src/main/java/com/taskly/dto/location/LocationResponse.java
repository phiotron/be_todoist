package com.taskly.dto.location;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** Một địa điểm đã lưu (M12). radius tính bằng mét. */
public record LocationResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double latitude,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double longitude,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Bán kính, mét (100–5000)") int radius,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt) {
}
