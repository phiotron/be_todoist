package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.project.ProjectResponse;
import com.taskly.dto.sharing.InvitationResponse;
import com.taskly.dto.sharing.InviteLinkResponse;
import com.taskly.dto.sharing.InviteRequest;
import com.taskly.dto.sharing.MoveToWorkspaceRequest;
import com.taskly.dto.sharing.ProjectMemberResponse;
import com.taskly.security.CurrentUser;
import com.taskly.service.ProjectService;
import com.taskly.service.SharingService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** M8a chia sẻ dự án (plan M8a F2, F5, F7, F8): Share, lời mời, link, rời / tham gia, chuyển vào team. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class SharingController {

    private final SharingService sharingService;
    private final ProjectService projectService;

    public SharingController(SharingService sharingService, ProjectService projectService) {
        this.sharingService = sharingService;
        this.projectService = projectService;
    }

    @GetMapping("/members")
    public List<ProjectMemberResponse> members(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {
        return sharingService.members(CurrentUser.id(jwt), projectId);
    }

    @DeleteMapping("/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId,
                             @PathVariable UUID userId) {
        sharingService.removeMember(CurrentUser.id(jwt), projectId, userId);
    }

    @GetMapping("/invitations")
    public List<InvitationResponse> invitations(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {
        return sharingService.invitations(CurrentUser.id(jwt), projectId);
    }

    @PostMapping("/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public InvitationResponse invite(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId,
                                     @Valid @RequestBody InviteRequest request) {
        return sharingService.invite(CurrentUser.id(jwt), projectId, request.email());
    }

    @GetMapping("/invite-link")
    public InviteLinkResponse inviteLink(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {
        return sharingService.inviteLink(CurrentUser.id(jwt), projectId);
    }

    @PutMapping("/invite-link")
    public InviteLinkResponse resetInviteLink(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {
        return sharingService.resetInviteLink(CurrentUser.id(jwt), projectId);
    }

    @DeleteMapping("/invite-link")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disableInviteLink(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {
        sharingService.disableInviteLink(CurrentUser.id(jwt), projectId);
    }

    @PostMapping("/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {
        sharingService.leave(CurrentUser.id(jwt), projectId);
    }

    @PostMapping("/join")
    public ProjectResponse join(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {
        return sharingService.join(CurrentUser.id(jwt), projectId);
    }

    @PostMapping("/move-to-workspace")
    public ProjectResponse moveToWorkspace(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId,
                                           @Valid @RequestBody MoveToWorkspaceRequest request) {
        return projectService.moveToWorkspace(CurrentUser.id(jwt), projectId, request);
    }
}
