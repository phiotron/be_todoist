package com.taskly.dto.auth;

import com.taskly.dto.user.UserResponse;

/** accessTokenExpiresIn tính bằng giây (900 = 15 phút). */
public record AuthResponse(String accessToken, String refreshToken, long accessTokenExpiresIn, UserResponse user) {
}
