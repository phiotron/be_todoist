package com.taskly.dto.label;

import com.taskly.entity.ProjectColor;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record CreateLabelRequest(
        @Schema(description = "Tuỳ chọn: id UUID do client sinh (idempotent theo id, spec 12.11); đã có + cùng nội dung → 200, khác → 409 id-conflict")
        UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "1–60 ký tự, không khoảng trắng, không @ % # / , ( ) | & ! \\ *; không trùng (bỏ qua hoa thường)")
        String name,
        @Schema(description = "Khoá màu, mặc định charcoal") ProjectColor color) {
}
