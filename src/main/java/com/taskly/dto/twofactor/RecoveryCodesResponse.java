package com.taskly.dto.twofactor;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 10 mã khôi phục dạng xxxx-xxxx — server chỉ trả MỘT lần (DB chỉ giữ hash). */
public record RecoveryCodesResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> recoveryCodes) {
}
