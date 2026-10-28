package com.taskly.dto.section;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.Optional;
import java.util.UUID;

/**
 * Move section (M11): projectId = dự án đích (bắt buộc; = dự án hiện tại thì chỉ đổi vị trí). afterId (Optional — spec
 * I3): VẮNG = cuối dự án đích, "afterId": null = ĐẦU.
 */
public record MoveSectionRequest(
        @NotNull(message = "{error.section-move-project-required}")
        @Schema(type = "string", format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID projectId,

        @Schema(type = "string", format = "uuid", nullable = true,
                description = "Vắng = cuối dự án đích; null = đầu; có giá trị = ngay sau section đó (của dự án đích)")
        Optional<UUID> afterId) {
}
