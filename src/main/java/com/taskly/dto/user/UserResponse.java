package com.taskly.dto.user;

import com.taskly.entity.IdentityProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/** Thông tin user trả cho client — KHÔNG có passwordHash. Mọi field luôn có mặt trong response. */
public record UserResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String email,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String displayName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String locale,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String timeZone,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Đã bật xác minh hai bước (M3)")
        boolean twoFactorEnabled,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "false với tài khoản chỉ đăng nhập bằng Google (đặt mật khẩu qua Quên mật khẩu)")
        boolean hasPassword,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Nhà cung cấp đăng nhập đã liên kết, ví dụ [\"GOOGLE\"]")
        List<IdentityProvider> linkedProviders,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                description = "M8b: đường dẫn ảnh đại diện (GET, cần Bearer), vd /api/v1/users/{id}/avatar?v=…; null = chưa có ảnh")
        String avatarUrl) {
}
