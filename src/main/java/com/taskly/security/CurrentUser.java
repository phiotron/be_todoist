package com.taskly.security;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/** Lấy id user đang đăng nhập từ access JWT (claim sub). Dùng với tham số @AuthenticationPrincipal Jwt. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID id(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
