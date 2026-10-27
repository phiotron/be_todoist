package com.taskly.dto.section;

import com.taskly.common.error.ProblemTexts;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Optional;
import java.util.UUID;

/**
 * Tạo section (spec 12.2). id: UUID tuỳ chọn do client sinh (spec 12.11). description null → "". afterId (Optional —
 * spec I3): VẮNG = cuối dự án, "afterId": null = ĐẦU (quyết định Q29).
 */
public record CreateSectionRequest(
        @Schema(type = "string", format = "uuid", description = "Tuỳ chọn: id do client sinh (idempotent theo id)")
        UUID id,

        @NotNull(message = ProblemTexts.SECTION_NAME_SIZE)
        @Size(min = 1, max = 2048, message = ProblemTexts.SECTION_NAME_SIZE)
        String name,

        @Size(max = 16383, message = ProblemTexts.DESCRIPTION_SIZE)
        String description,

        @Schema(type = "string", format = "uuid", nullable = true,
                description = "Vắng = cuối dự án; null = đầu; có giá trị = ngay sau section đó")
        Optional<UUID> afterId) {

    public CreateSectionRequest {
        name = name == null ? null : name.strip();
    }
}
