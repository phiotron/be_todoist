package com.taskly.dto.auth;

import com.taskly.dto.user.UserResponse;
import io.swagger.v3.oas.annotations.media.Schema;

/** Mọi field luôn có mặt trong response — đánh REQUIRED để types.gen.ts của mobile không sinh kiểu optional. */
public record AuthResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String accessToken,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String refreshToken,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Số giây access token còn sống")
        long accessTokenExpiresIn,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UserResponse user) {
}
