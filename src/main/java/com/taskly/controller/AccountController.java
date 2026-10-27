package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.security.CurrentUser;
import com.taskly.service.AuthService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Thao tác trên cả tài khoản (spec 8.1): đăng xuất mọi thiết bị; Task 12k thêm DELETE me. */
@RestController
@RequestMapping("/api/v1/me")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class AccountController {

    private final AuthService authService;

    public AccountController(AuthService authService) {
        this.authService = authService;
    }

    /** Thu hồi mọi refresh token của user → 204. Access token đang cầm vẫn sống tới khi hết hạn (≤ 15 phút). */
    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logoutAll(@AuthenticationPrincipal Jwt jwt) {
        authService.revokeAllRefreshTokens(CurrentUser.id(jwt));
    }
}
