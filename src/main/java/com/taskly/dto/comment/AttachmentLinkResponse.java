package com.taskly.dto.comment;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** Link tải file ký HMAC, không cần Bearer, sống ngắn (M8b). url tương đối với gốc API. */
public record AttachmentLinkResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                example = "/api/v1/attachments/0b0e…/download?expires=1793000000&sig=…") String url,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant expiresAt) {
}
