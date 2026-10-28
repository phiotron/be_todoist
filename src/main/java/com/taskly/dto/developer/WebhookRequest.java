package com.taskly.dto.developer;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record WebhookRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "URL https nhận webhook") String url,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "item:added|updated|deleted|completed|uncompleted, project:added|updated|deleted|archived|unarchived, "
                        + "section:(như project), label:added|updated|deleted, filter:added|updated|deleted") List<String> events) {
}
