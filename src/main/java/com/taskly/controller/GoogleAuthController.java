package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.auth.AuthResponse;
import com.taskly.dto.auth.GoogleSignInRequest;
import com.taskly.dto.user.GoogleLinkRequest;
import com.taskly.security.CurrentUser;
import com.taskly.service.GoogleIdentityService;
import io.swagger.v3.oas.annotations.Operation;
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
    @Operation(summary = "Đăng nhập / đăng ký bằng Google",
            description = "Xác minh ID token Google. 200 AuthResponse (kể cả lần đầu tạo tài khoản). "
                    + "401 token sai / hết hạn / sai aud; 403 email Google chưa xác minh; 503 không kết nối được Google. "
                    + "409 khi email đã có tài khoản mật khẩu: ProblemDetail có code = \"link-required\" và linkToken "
                    + "(hạn 10 phút) — đăng nhập bằng mật khẩu rồi gọi POST /api/v1/me/identities/google {linkToken}.")
    @PostMapping("/auth/google")
    public AuthResponse signIn(@Valid @RequestBody GoogleSignInRequest request) {
        return googleIdentityService.signIn(request.idToken());
    }

    /** Cần access JWT. 204 khi liên kết xong (app đọc lại GET me). Token Google không dùng được → 400, không 401. */
    @Operation(summary = "Liên kết Google vào tài khoản đang đăng nhập",
            description = "Gửi đúng một trong idToken hoặc linkToken. 204 khi xong (đọc lại GET me). 400 token không "
                    + "dùng được / hết hạn / sai aud hoặc email trong linkToken khác email tài khoản; 403 email Google "
                    + "chưa xác minh; 409 Google đã gắn tài khoản khác hoặc tài khoản đã gắn Google khác.")
    @PostMapping("/me/identities/google")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    public void link(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody GoogleLinkRequest request) {
        googleIdentityService.link(CurrentUser.id(jwt), request);
    }

    /** Cần access JWT. 204; chưa liên kết → 404; tài khoản chưa có mật khẩu → 409. */
    @Operation(summary = "Bỏ liên kết Google",
            description = "204; 404 nếu chưa liên kết Google; 409 nếu tài khoản chưa có mật khẩu.")
    @DeleteMapping("/me/identities/google")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    public void unlink(@AuthenticationPrincipal Jwt jwt) {
        googleIdentityService.unlink(CurrentUser.id(jwt));
    }
}
