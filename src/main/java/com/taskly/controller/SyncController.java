package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.sync.SyncRequest;
import com.taskly.dto.sync.SyncResponse;
import com.taskly.security.CurrentUser;
import com.taskly.service.sync.SyncService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * M9 — đồng bộ kiểu Sync API (plan 2026-10-27-m9-chuc-nang.md F2 / F3): gửi lệnh (≤ 100, mỗi lệnh uuid chống trùng) +
 * nhận thay đổi theo syncToken. Luôn 200 khi request hợp lệ; lỗi từng lệnh nằm trong results.
 */
@RestController
@RequestMapping("/api/v1/sync")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class SyncController {

    private final SyncService syncService;

    public SyncController(SyncService syncService) {
        this.syncService = syncService;
    }

    @PostMapping
    public SyncResponse sync(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SyncRequest request) {
        return syncService.sync(CurrentUser.id(jwt), request, LocaleContextHolder.getLocale());
    }
}
