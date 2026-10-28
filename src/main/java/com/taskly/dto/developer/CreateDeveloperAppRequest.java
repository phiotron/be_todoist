package com.taskly.dto.developer;

import io.swagger.v3.oas.annotations.media.Schema;

public record CreateDeveloperAppRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "1-100 ký tự") String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "URL tuyệt đối: https, hoặc http chỉ cho localhost, hoặc scheme riêng của app") String redirectUri) {
}
