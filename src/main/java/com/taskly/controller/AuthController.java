package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.auth.AuthResponse;
import com.taskly.dto.auth.ForgotPasswordRequest;
import com.taskly.dto.auth.LoginRequest;
import com.taskly.dto.auth.LogoutRequest;
import com.taskly.dto.auth.RefreshRequest;
import com.taskly.dto.auth.RegisterRequest;
import com.taskly.dto.auth.ResetPasswordRequest;
import com.taskly.dto.auth.TwoFactorVerifyRequest;
import com.taskly.security.AuthRateLimiter;
import com.taskly.security.CurrentUser;
import com.taskly.service.AuthService;
import com.taskly.service.PasswordResetService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthRateLimiter authRateLimiter;
    private final PasswordResetService passwordResetService;

    public AuthController(AuthService authService, AuthRateLimiter authRateLimiter,
                          PasswordResetService passwordResetService) {
        this.authService = authService;
        this.authRateLimiter = authRateLimiter;
        this.passwordResetService = passwordResetService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        authRateLimiter.check("register", httpRequest);
        return authService.register(request);
    }

    /** Tài khoản bật 2FA → 401 code "two-factor-required" kèm twoFactorToken (gọi tiếp /2fa/verify). */
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        authRateLimiter.check("login", httpRequest);
        return authService.login(request);
    }

    /** Public. Bước 2 của đăng nhập 2FA: {twoFactorToken, code} → cặp token; sai / hết hạn → 401. */
    @PostMapping("/2fa/verify")
    public AuthResponse verifyTwoFactor(@Valid @RequestBody TwoFactorVerifyRequest request) {
        return authService.verifyTwoFactor(request);
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request, HttpServletRequest httpRequest) {
        authRateLimiter.checkRefresh(httpRequest);
        return authService.refresh(request);
    }

    /** Cần access JWT (SecurityConfig không để /logout ở danh sách public). */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    public void logout(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody LogoutRequest request) {
        authService.logout(CurrentUser.id(jwt), request);
    }

    /** Public. Luôn 200 body rỗng dù email có tài khoản hay không; gửi thư lỗi → 503. */
    @PostMapping("/password-reset/request")
    @ResponseStatus(HttpStatus.OK)
    public void requestPasswordReset(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordResetService.requestReset(request.email());
    }

    /** Public. 204 (không tự đăng nhập); mã sai 404; hết hạn / đã dùng 410. */
    @PostMapping("/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmPasswordReset(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.confirmReset(request);
    }
}
