package com.taskly.dto.activity;

import com.taskly.entity.ActivityEventType;
import com.taskly.entity.ActivityObjectType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * Một dòng Activity log (GET /api/v1/activity, M10). objectName / projectName là ẢNH CHỤP tên lúc ghi (đối tượng đã xoá
 * vẫn hiện được). projectId: task / section = dự án chứa; dự án = chính nó. initiatorId null = người đã xoá tài khoản.
 */
public record ActivityResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ActivityObjectType objectType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID objectId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ActivityEventType eventType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) UUID projectId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String objectName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String projectName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) UUID initiatorId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt) {
}
