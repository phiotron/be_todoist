package com.taskly.dto.project;

import com.taskly.common.error.ProblemTexts;
import com.taskly.entity.ProjectColor;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Optional;
import java.util.UUID;

/**
 * Tạo dự án (spec 12.2). id: UUID tuỳ chọn do client sinh (spec 12.11; không phải UUID → 400). color null → charcoal;
 * description null → "". parentId null → dự án gốc. afterId (Optional — spec I3): VẮNG = cuối vùng chứa (workspace,
 * parentId), "afterId": null = ĐẦU (quyết định Q29). Giá trị màu ngoài enum → Jackson báo 400.
 */
public record CreateProjectRequest(
        @Schema(type = "string", format = "uuid", description = "Tuỳ chọn: id do client sinh (idempotent theo id)")
        UUID id,

        @NotNull(message = ProblemTexts.PROJECT_NAME_SIZE)
        @Size(min = 1, max = 120, message = ProblemTexts.PROJECT_NAME_SIZE)
        String name,

        ProjectColor color,

        @Size(max = 16383, message = ProblemTexts.DESCRIPTION_SIZE)
        String description,

        UUID parentId,

        @Schema(type = "string", format = "uuid", nullable = true,
                description = "Vắng = cuối vùng chứa; null = đầu; có giá trị = ngay sau dự án đó")
        Optional<UUID> afterId) {

    /** Cắt khoảng trắng hai đầu TRƯỚC khi validate ("   " → "" → lỗi @Size). */
    public CreateProjectRequest {
        name = name == null ? null : name.strip();
    }
}
