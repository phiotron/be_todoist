package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.auth.AuthResponse;
import com.taskly.dto.auth.GoogleSignInRequest;
import com.taskly.dto.user.GoogleLinkRequest;
import com.taskly.security.CurrentUser;
import com.taskly.service.GoogleIdentityService;
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

/** Đăng nhập Google (public) và liên kết Google vào tài khoản đang đăng nhập (cần JWT). */
@RestController
@RequestMapping("/api/v1")
public class GoogleAuthController {

    private final GoogleIdentityService googleIdentityService;

    public GoogleAuthController(GoogleIdentityService googleIdentityService) {
        this.googleIdentityService = googleIdentityService;
    }

    /** Luôn 200 khi thành công — kể cả lần đầu tạo tài khoản (app không cần phân biệt). */
    @PostMapping("/auth/google")
    public AuthResponse signIn(@Valid @RequestBody GoogleSignInRequest request) {
        return googleIdentityService.signIn(request.idToken());
    }

    /** Cần access JWT. 204 khi liên kết xong (app đọc lại GET me). Token Google không dùng được → 400, không 401. */
    @PostMapping("/me/identities/google")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    public void link(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody GoogleLinkRequest request) {
        googleIdentityService.link(CurrentUser.id(jwt), request);
    }

    /** Cần access JWT. 204; chưa liên kết → 404; tài khoản chưa có mật khẩu → 409. */
    @DeleteMapping("/me/identities/google")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    public void unlink(@AuthenticationPrincipal Jwt jwt) {
        googleIdentityService.unlink(CurrentUser.id(jwt));
    }
}
