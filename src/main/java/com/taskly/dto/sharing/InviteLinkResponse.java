package com.taskly.dto.sharing;

import io.swagger.v3.oas.annotations.media.Schema;

/** M8a: link mời (dùng nhiều lần). token null = link đang tắt. url = taskly://join/&lt;token&gt;. */
public record InviteLinkResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String token,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String url) {
}
