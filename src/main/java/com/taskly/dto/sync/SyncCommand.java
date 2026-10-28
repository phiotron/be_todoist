package com.taskly.dto.sync;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Một lệnh của POST sync (M9, plan F2). uuid chống gửi trùng (lệnh đã chạy thành công không chạy lại); type = tên lệnh
 * snake_case (task_add, project_update…); tempId: id tạm của bản ghi mà lệnh tạo — là UUID thì dùng luôn làm id, khác thì
 * server sinh; lệnh sau trong cùng request nhắc tới tempId được thay bằng id thật. args = body REST tương ứng (+ id).
 */
public record SyncCommand(
        @NotNull(message = "{error.sync-uuid-required}")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "UUID chống gửi trùng")
        UUID uuid,

        @NotBlank(message = "{error.sync-command-unknown}")
        @Size(max = 64, message = "{error.sync-command-unknown}")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "task_add")
        String type,

        @Size(max = 64, message = "{error.sync-temp-id-size}")
        @Schema(description = "Id tạm của bản ghi lệnh này tạo (≤ 64 ký tự)")
        String tempId,

        @Schema(description = "Tham số lệnh: body REST tương ứng, thêm id của bản ghi khi sửa / xoá", type = "object")
        JsonNode args) {
}
