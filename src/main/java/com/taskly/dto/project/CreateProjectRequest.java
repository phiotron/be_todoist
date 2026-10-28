package com.taskly.dto.project;

import com.taskly.entity.ProjectAccess;
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

        @NotNull(message = "{error.project-name-size}")
        @Size(min = 1, max = 120, message = "{error.project-name-size}")
        String name,

        ProjectColor color,

        @Size(max = 16383, message = "{error.description-size}")
        String description,

        UUID parentId,

        @Schema(type = "string", format = "uuid", nullable = true,
                description = "Vắng = cuối vùng chứa; null = đầu; có giá trị = ngay sau dự án đó")
        Optional<UUID> afterId,

        @Schema(type = "string", format = "uuid",
                description = "M8a: workspace đích (vắng = workspace cá nhân); workspace nhóm cần vai trò Admin / Member")
        UUID workspaceId,

        @Schema(description = "M8a: chỉ dự án nhóm — TEAM (mặc định) | RESTRICTED")
        ProjectAccess access,

        @Schema(type = "string", format = "uuid", description = "M8a: folder của workspace nhóm")
        UUID folderId) {

    /** Cắt khoảng trắng hai đầu TRƯỚC khi validate ("   " → "" → lỗi @Size). */
    public CreateProjectRequest {
        name = name == null ? null : name.strip();
    }
}
