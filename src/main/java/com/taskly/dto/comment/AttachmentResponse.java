package com.taskly.dto.comment;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** File đính kèm của comment (M8b). Tải về: GET attachments/{id}/link → url ký sẵn. */
public record AttachmentResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String fileName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String contentType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long sizeBytes,
        @JsonProperty("isImage") @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "image/jpeg | png | gif | webp → app hiện ảnh thu nhỏ") boolean isImage) {
}
