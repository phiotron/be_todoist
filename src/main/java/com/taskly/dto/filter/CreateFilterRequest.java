package com.taskly.dto.filter;

import com.taskly.entity.ProjectColor;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record CreateFilterRequest(
        @Schema(description = "Tuỳ chọn: id UUID do client sinh (idempotent theo id, spec 12.11); đã có + cùng nội dung → 200, khác → 409 id-conflict")
        UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "1–120 ký tự sau khi cắt") String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Cú pháp filter, 1–1024 ký tự") String query,
        @Schema(description = "Khoá màu, mặc định charcoal") ProjectColor color) {
}
