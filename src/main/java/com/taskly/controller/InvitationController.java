package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.sharing.AcceptInvitationRequest;
import com.taskly.dto.sharing.AcceptInvitationResponse;
import com.taskly.dto.sharing.InvitationResponse;
import com.taskly.security.CurrentUser;
import com.taskly.service.InvitationService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** M8a lời mời của người nhận (plan M8a F3): danh sách, chấp nhận (token / id), từ chối, thu hồi. */
@RestController
@RequestMapping("/api/v1/invitations")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class InvitationController {

    private final InvitationService invitationService;

    public InvitationController(InvitationService invitationService) {
        this.invitationService = invitationService;
    }

    /** Lời mời email đang chờ gửi tới email của người gọi. */
    @GetMapping
    public List<InvitationResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        return invitationService.mine(CurrentUser.id(jwt));
    }

    /** Token thư mời hoặc token link (taskly://join/&lt;token&gt;). */
    @PostMapping("/accept")
    public AcceptInvitationResponse accept(@AuthenticationPrincipal Jwt jwt,
                                           @Valid @RequestBody AcceptInvitationRequest request) {
        return invitationService.acceptToken(CurrentUser.id(jwt), request.token());
    }

    @PostMapping("/{invitationId}/accept")
    public AcceptInvitationResponse acceptById(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID invitationId) {
        return invitationService.acceptById(CurrentUser.id(jwt), invitationId);
    }

    @PostMapping("/{invitationId}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decline(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID invitationId) {
        invitationService.decline(CurrentUser.id(jwt), invitationId);
    }

    @DeleteMapping("/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID invitationId) {
        invitationService.revoke(CurrentUser.id(jwt), invitationId);
    }
}
