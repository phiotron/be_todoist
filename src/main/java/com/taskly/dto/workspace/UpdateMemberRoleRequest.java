package com.taskly.dto.workspace;

import com.taskly.entity.WorkspaceRole;
import jakarta.validation.constraints.NotNull;

/** M8a: PATCH workspaces/{id}/members/{userId} — chỉ Admin. */
public record UpdateMemberRoleRequest(@NotNull(message = "{validation.invalid}") WorkspaceRole role) {
}
