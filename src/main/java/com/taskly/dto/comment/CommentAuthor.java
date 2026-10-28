package com.taskly.dto.comment;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** Người viết comment / người có thể được @mention / người gây ra thông báo (M8b). */
public record CommentAuthor(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                description = "Đường dẫn ảnh đại diện (cần Bearer) hoặc null") String avatarUrl) {
}
