package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.email.EmailAddressResponse;
import com.taskly.security.CurrentUser;
import com.taskly.service.EmailInboxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Địa chỉ nhận thư (M13a): "Email tasks to this project" / "Email tasks to Inbox" (Inbox là một dự án) và "Email to this
 * task". PUT = lấy hoặc tạo (gọi lại trả cùng địa chỉ); POST …/regenerate = địa chỉ mới, địa chỉ cũ hết hiệu lực.
 */
@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class EmailAddressController {

    private final EmailInboxService emailInboxService;

    public EmailAddressController(EmailInboxService emailInboxService) {
        this.emailInboxService = emailInboxService;
    }

    @PutMapping("/projects/{projectId}/email-address")
    @Operation(summary = "Lấy hoặc tạo địa chỉ nhận thư của dự án / Inbox (thư tới → task mới)")
    public EmailAddressResponse projectAddress(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {
        return emailInboxService.projectAddress(CurrentUser.id(jwt), projectId);
    }

    @PostMapping("/projects/{projectId}/email-address/regenerate")
    @Operation(summary = "Tạo địa chỉ mới cho dự án / Inbox (địa chỉ cũ hết hiệu lực)")
    public EmailAddressResponse regenerateProjectAddress(@AuthenticationPrincipal Jwt jwt,
                                                         @PathVariable UUID projectId) {
        return emailInboxService.regenerateProjectAddress(CurrentUser.id(jwt), projectId);
    }

    @PutMapping("/tasks/{taskId}/email-address")
    @Operation(summary = "Lấy hoặc tạo địa chỉ nhận thư của task (thư tới → thêm vào task)")
    public EmailAddressResponse taskAddress(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID taskId) {
        return emailInboxService.taskAddress(CurrentUser.id(jwt), taskId);
    }

    @PostMapping("/tasks/{taskId}/email-address/regenerate")
    @Operation(summary = "Tạo địa chỉ mới cho task (địa chỉ cũ hết hiệu lực)")
    public EmailAddressResponse regenerateTaskAddress(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID taskId) {
        return emailInboxService.regenerateTaskAddress(CurrentUser.id(jwt), taskId);
    }
}
