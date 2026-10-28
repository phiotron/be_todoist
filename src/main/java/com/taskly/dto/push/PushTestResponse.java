package com.taskly.dto.push;

import io.swagger.v3.oas.annotations.media.Schema;

public record PushTestResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Số thiết bị đã đăng ký nhận push của tài khoản") int devices) {
}
