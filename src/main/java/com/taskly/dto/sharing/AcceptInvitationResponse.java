package com.taskly.dto.sharing;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** M8a: kết quả chấp nhận lời mời — app mở dự án (kind PROJECT) hoặc tab Team (kind WORKSPACE). */
public record AcceptInvitationResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"PROJECT", "WORKSPACE"}) String kind,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) UUID projectId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID workspaceId) {
}
