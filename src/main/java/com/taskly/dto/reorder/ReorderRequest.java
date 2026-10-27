package com.taskly.dto.reorder;

import com.taskly.common.error.ProblemTexts;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.Optional;
import java.util.UUID;

/**
 * "Đặt id ngay sau afterId" (spec 12.4). afterId BẮT BUỘC có khoá: vắng → 400; "afterId": null → lên đầu vùng chứa
 * (Optional.empty()); khác vùng chứa → 400. Optional phân biệt "vắng" (null của Java) với "null tường minh" (spec I3).
 */
public record ReorderRequest(
        @NotNull(message = ProblemTexts.REORDER_TYPE_REQUIRED)
        ReorderType type,

        @NotNull(message = ProblemTexts.REORDER_ID_REQUIRED)
        UUID id,

        @NotNull(message = ProblemTexts.REORDER_AFTER_ID_REQUIRED)
        @Schema(type = "string", format = "uuid", nullable = true,
                description = "BẮT BUỘC có khoá: id đứng trước, hoặc null = lên đầu vùng chứa")
        Optional<UUID> afterId) {
}
