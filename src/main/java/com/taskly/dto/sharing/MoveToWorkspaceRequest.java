package com.taskly.dto.sharing;

import com.taskly.entity.ProjectAccess;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** M8a: POST projects/{id}/move-to-workspace — chỉ chiều cá nhân → nhóm (không chuyển ra). */
public record MoveToWorkspaceRequest(
        @NotNull(message = "{validation.invalid}") UUID workspaceId,
        @Schema(description = "TEAM (mặc định) | RESTRICTED") ProjectAccess access,
        UUID folderId) {
}
