package com.taskly.dto.developer;

import io.swagger.v3.oas.annotations.media.Schema;

/** Trường vắng hoặc null = giữ nguyên. */
public record UpdateDeveloperAppRequest(
        @Schema(description = "1-100 ký tự") String name,
        @Schema(description = "Redirect URI mới") String redirectUri) {
}
