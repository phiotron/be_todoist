package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.settings.SettingsResponse;
import com.taskly.dto.settings.UpdateSettingsRequest;
import com.taskly.security.CurrentUser;
import com.taskly.service.SettingsService;
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

/** Cài đặt theo tài khoản: /api/v1/me/settings (tách khỏi UserController để mỗi file một trách nhiệm). */
@RestController
@RequestMapping("/api/v1/me/settings")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public SettingsResponse get(@AuthenticationPrincipal Jwt jwt) {
        return settingsService.get(CurrentUser.id(jwt));
    }

    @PatchMapping
    public SettingsResponse update(@AuthenticationPrincipal Jwt jwt,
                                   @Valid @RequestBody UpdateSettingsRequest request) {
        return settingsService.update(CurrentUser.id(jwt), request);
    }

    /** Về mặc định (SYSTEM, vi, giữ múi giờ). */
    @PostMapping("/reset")
    public SettingsResponse reset(@AuthenticationPrincipal Jwt jwt) {
        return settingsService.reset(CurrentUser.id(jwt));
    }
}
