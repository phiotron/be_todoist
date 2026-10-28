package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.backup.BackupResponse;
import com.taskly.security.CurrentUser;
import com.taskly.service.BackupService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** M11 F8: danh sách backup hằng ngày (mới nhất trước) — chỉ xem; không tải / khôi phục (ADR 0006). */
@RestController
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class BackupController {

    private final BackupService backupService;

    public BackupController(BackupService backupService) {
        this.backupService = backupService;
    }

    @GetMapping("/api/v1/backups")
    public List<BackupResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return backupService.list(CurrentUser.id(jwt));
    }
}
