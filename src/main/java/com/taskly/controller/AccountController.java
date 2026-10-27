package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.user.DeleteAccountRequest;
import com.taskly.security.CurrentUser;
import com.taskly.service.AccountDeletionService;
import com.taskly.service.AuthService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Thao tác trên cả tài khoản (spec 8.1): đăng xuất mọi thiết bị, xoá tài khoản. */
@RestController
@RequestMapping("/api/v1/me")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class AccountController {

    private final AuthService authService;
    private final AccountDeletionService accountDeletionService;

    public AccountController(AuthService authService, AccountDeletionService accountDeletionService) {
        this.authService = authService;
        this.accountDeletionService = accountDeletionService;
    }

    /** Thu hồi mọi refresh token của user → 204. Access token đang cầm vẫn sống tới khi hết hạn (≤ 15 phút). */
    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logoutAll(@AuthenticationPrincipal Jwt jwt) {
        authService.revokeAllRefreshTokens(CurrentUser.id(jwt));
    }

    /**
     * Xoá vĩnh viễn tài khoản + mọi dữ liệu → 204. Body {password, code?}; sai → 400; chưa có mật khẩu → 409
     * password-required. App hỏi xác nhận + mật khẩu trước khi gọi (BR-11).
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccount(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DeleteAccountRequest request) {
        accountDeletionService.deleteAccount(CurrentUser.id(jwt), request);
    }
}
