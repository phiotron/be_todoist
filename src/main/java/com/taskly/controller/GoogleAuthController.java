package com.taskly.controller;

import com.taskly.dto.auth.AuthResponse;
import com.taskly.dto.auth.GoogleSignInRequest;
import com.taskly.service.GoogleIdentityService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
