package com.taskly.security;

import com.taskly.common.error.ErrorMessage;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/** Lấy id user đang đăng nhập từ access JWT (claim sub). Dùng với tham số @AuthenticationPrincipal Jwt. */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** sub không phải UUID (chỉ xảy ra khi bên ký token sai) → lỗi xác thực 401, không phải 500. */
    public static UUID id(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidBearerTokenException(ErrorMessage.SESSION_INVALID.key());
        }
    }
}
