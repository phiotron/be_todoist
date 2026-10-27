package com.taskly.dto.project;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.taskly.entity.ProjectColor;
import com.taskly.entity.ViewStyle;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** Spec 12.2. Mọi field luôn có mặt (REQUIRED); field có thể null đánh nullable để types.gen.ts sinh "T | null". */
public record ProjectResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, description = "null với Inbox")
        ProjectColor color,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "\"\" khi không có mô tả") String description,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, description = "null = dự án gốc")
        UUID parentId,
        @JsonProperty("isInbox") @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isInbox,
        @JsonProperty("isFavorite") @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isFavorite,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Instant archivedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long sortOrder,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ViewStyle viewStyle,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Số task ĐANG MỞ (gồm sub-task) của riêng dự án này, không cộng dự án con")
        long activeTaskCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Số dự án con cháu mọi cấp, KỂ CẢ đã lưu trữ (hộp xác nhận xoá dự án — spec 10.1)")
        long descendantProjectCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Tổng task ĐANG MỞ của các dự án con cháu, KHÔNG gồm chính dự án: số task hiện trong hộp xoá = activeTaskCount + số này")
        long descendantActiveTaskCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant updatedAt) {
}
