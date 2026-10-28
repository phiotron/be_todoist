package com.taskly.dto.comment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** POST comments/{id}/reactions (M8b). emoji: 1–16 ký tự, không khoảng trắng (kiểm ở CommentService). */
public record ReactionRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "👍")
        @NotBlank(message = "{validation.reaction.emoji.required}")
        String emoji) {
}
