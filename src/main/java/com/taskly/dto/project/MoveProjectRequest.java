package com.taskly.dto.project;

import com.taskly.common.error.ProblemTexts;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.Optional;
import java.util.UUID;

/**
 * POST projects/{id}/move (spec 12.2, I3): parentId BẮT BUỘC có khoá (vắng → 400; "parentId": null = thành dự án gốc);
 * afterId vắng = cuối vùng chứa mới, "afterId": null = đầu (quyết định Q29). Cả cây dự án con đi theo.
 */
public record MoveProjectRequest(
        @NotNull(message = ProblemTexts.MOVE_PARENT_ID_REQUIRED)
        @Schema(type = "string", format = "uuid", nullable = true,
                description = "BẮT BUỘC có khoá: dự án cha mới, hoặc null = dự án gốc")
        Optional<UUID> parentId,

        @Schema(type = "string", format = "uuid", nullable = true,
                description = "Vắng = cuối vùng chứa; null = đầu; có giá trị = ngay sau dự án đó")
        Optional<UUID> afterId) {
}
