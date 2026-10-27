package com.taskly.dto.reorder;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.Optional;
import java.util.UUID;

/**
 * "Đặt id ngay sau afterId" (spec 12.4). afterId BẮT BUỘC có khoá: vắng → 400; "afterId": null → lên đầu vùng chứa
 * (Optional.empty()); khác vùng chứa → 400. Optional phân biệt "vắng" (null của Java) với "null tường minh" (spec I3).
 */
public record ReorderRequest(
        @NotNull(message = "{error.reorder-type-required}")
        ReorderType type,

        @NotNull(message = "{error.reorder-id-required}")
        UUID id,

        @NotNull(message = "{error.reorder-after-id-required}")
        @Schema(type = "string", format = "uuid", nullable = true,
                description = "BẮT BUỘC có khoá: id đứng trước, hoặc null = lên đầu vùng chứa")
        Optional<UUID> afterId) {
}
