package com.taskly.dto.email;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Kết quả xử lý một thư theo từng địa chỉ Taskly trong {@code to} (M13a): created = task mới, appended = nối vào task,
 * duplicates = message-id đã xử lý, ignored = không phải địa chỉ Taskly còn hiệu lực / thư rỗng, rejected = đích không
 * ghi được (dự án lưu trữ, vượt giới hạn…), rateLimited = địa chỉ vượt giới hạn tần suất.
 */
public record InboundEmailResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int created,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int appended,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int duplicates,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int ignored,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int rejected,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int rateLimited) {
}
