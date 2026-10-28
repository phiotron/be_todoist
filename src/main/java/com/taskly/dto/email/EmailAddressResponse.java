package com.taskly.dto.email;

import com.taskly.entity.EmailTargetType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** Địa chỉ nhận thư của người dùng cho một dự án (kể cả Inbox) hoặc một task (M13a). */
public record EmailAddressResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Địa chỉ đầy đủ, vd abcdefghijklmnopqrstuvwxyz@in.example.com — bí mật: ai biết thì thêm "
                        + "được task; tạo lại bằng POST …/email-address/regenerate")
        String address,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) EmailTargetType targetType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID targetId) {
}
