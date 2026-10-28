package com.taskly.dto.backup;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** Một bản backup hằng ngày (M11) — chỉ thông tin để xem; không tải / khôi phục trên mobile (ADR 0006). */
public record BackupResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int projectCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int taskCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long sizeBytes) {
}
