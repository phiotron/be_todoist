package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.auth.AuthResponse;
import com.taskly.dto.user.ChangePasswordRequest;
import com.taskly.dto.user.UpdateProfileRequest;
import com.taskly.dto.user.UserResponse;
import com.taskly.security.CurrentUser;
import com.taskly.service.AuthService;
import com.taskly.service.UserService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class UserController {

    private final UserService userService;
    private final AuthService authService;

    public UserController(UserService userService, AuthService authService) {
        this.userService = userService;
        this.authService = authService;
    }

    @GetMapping
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return userService.getProfile(CurrentUser.id(jwt));
    }

    /** Đổi tên hiển thị (1–100 ký tự sau khi cắt khoảng trắng). */
    @PatchMapping
    public UserResponse updateProfile(@AuthenticationPrincipal Jwt jwt,
                                      @Valid @RequestBody UpdateProfileRequest request) {
        return userService.updateProfile(CurrentUser.id(jwt), request);
    }

    /** Đổi mật khẩu: sai mật khẩu hiện tại → 400; đúng → thu hồi mọi refresh token, trả cặp token mới. */
    @PostMapping("/password")
    public AuthResponse changePassword(@AuthenticationPrincipal Jwt jwt,
                                       @Valid @RequestBody ChangePasswordRequest request) {
        return authService.changePassword(CurrentUser.id(jwt), request);
    }
}
