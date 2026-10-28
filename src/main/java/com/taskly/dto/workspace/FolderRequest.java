package com.taskly.dto.workspace;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** M8a: tạo / đổi tên folder (1–120 ký tự). */
public record FolderRequest(
        @NotNull(message = "{error.folder-name-size}")
        @Size(min = 1, max = 120, message = "{error.folder-name-size}")
        String name) {

    public FolderRequest {
        name = name == null ? null : name.strip();
    }
}
