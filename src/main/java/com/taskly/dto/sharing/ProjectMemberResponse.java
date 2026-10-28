package com.taskly.dto.sharing;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** M8a: một người trong "In this project". isOwner: chủ dự án cá nhân / người tạo dự án nhóm. */
public record ProjectMemberResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID userId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String email,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String displayName,
        @JsonProperty("isOwner") @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isOwner,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                description = "Đường dẫn ảnh đại diện (GET, cần Bearer) hoặc null = chưa có ảnh (như UserResponse.avatarUrl)")
        String avatarUrl) {
}
