package com.taskly.dto.project;

import com.taskly.entity.ProjectAccess;
import com.taskly.entity.ProjectColor;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.util.Optional;
import java.util.UUID;

/**
 * Field null = không đổi; description "" = xoá mô tả. Dự án dùng "ghi sau thắng" (không version) — spec 5.6.
 * Đổi cha dùng POST projects/{id}/move (Task 13). M8a: folderId (vắng = giữ, null = bỏ folder) và access chỉ dành cho
 * dự án nhóm và người quản lý dự án.
 */
public record UpdateProjectRequest(
        @Size(min = 1, max = 120, message = "{error.project-name-size}")
        String name,

        ProjectColor color,

        @Size(max = 16383, message = "{error.description-size}")
        String description,

        @Schema(type = "string", format = "uuid", nullable = true,
                description = "M8a: vắng = giữ nguyên; null = bỏ khỏi folder; có giá trị = chuyển vào folder đó")
        Optional<UUID> folderId,

        @Schema(description = "M8a: TEAM | RESTRICTED (chỉ dự án nhóm); null / vắng = giữ")
        ProjectAccess access) {

    public UpdateProjectRequest {
        name = name == null ? null : name.strip();
    }
}
