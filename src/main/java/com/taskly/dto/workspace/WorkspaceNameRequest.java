package com.taskly.dto.workspace;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** M8a: POST workspaces / PATCH workspaces/{id} — tên nhóm 1–100 ký tự (cắt khoảng trắng hai đầu trước khi kiểm). */
public record WorkspaceNameRequest(
        @NotNull(message = "{error.workspace-name-size}")
        @Size(min = 1, max = 100, message = "{error.workspace-name-size}")
        String name) {

    public WorkspaceNameRequest {
        name = name == null ? null : name.strip();
    }
}
