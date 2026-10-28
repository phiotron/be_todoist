package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.sharing.InvitationResponse;
import com.taskly.dto.sharing.InviteLinkResponse;
import com.taskly.dto.sharing.InviteRequest;
import com.taskly.dto.workspace.FolderRequest;
import com.taskly.dto.workspace.FolderResponse;
import com.taskly.dto.workspace.TeamProjectResponse;
import com.taskly.dto.workspace.UpdateMemberRoleRequest;
import com.taskly.dto.workspace.WorkspaceMemberResponse;
import com.taskly.dto.workspace.WorkspaceNameRequest;
import com.taskly.dto.workspace.WorkspaceResponse;
import com.taskly.security.CurrentUser;
import com.taskly.service.TeamService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** M8a workspace nhóm (spec 12.9 M8a; plan M8a F6, F7). Quyền: TeamService → AccessPolicy. */
@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class WorkspaceController {

    private final TeamService teamService;

    public WorkspaceController(TeamService teamService) {
        this.teamService = teamService;
    }

    @GetMapping("/workspaces")
    public List<WorkspaceResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return teamService.list(CurrentUser.id(jwt));
    }

    @PostMapping("/workspaces")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody WorkspaceNameRequest request) {
        return teamService.create(CurrentUser.id(jwt), request.name());
    }

    @GetMapping("/workspaces/{workspaceId}")
    public WorkspaceResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId) {
        return teamService.get(CurrentUser.id(jwt), workspaceId);
    }

    @PatchMapping("/workspaces/{workspaceId}")
    public WorkspaceResponse rename(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                                    @Valid @RequestBody WorkspaceNameRequest request) {
        return teamService.rename(CurrentUser.id(jwt), workspaceId, request.name());
    }

    @DeleteMapping("/workspaces/{workspaceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId) {
        teamService.delete(CurrentUser.id(jwt), workspaceId);
    }

    @GetMapping("/workspaces/{workspaceId}/members")
    public List<WorkspaceMemberResponse> members(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId) {
        return teamService.members(CurrentUser.id(jwt), workspaceId);
    }

    @PatchMapping("/workspaces/{workspaceId}/members/{userId}")
    public List<WorkspaceMemberResponse> changeRole(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                                                    @PathVariable UUID userId,
                                                    @Valid @RequestBody UpdateMemberRoleRequest request) {
        return teamService.changeRole(CurrentUser.id(jwt), workspaceId, userId, request.role());
    }

    @DeleteMapping("/workspaces/{workspaceId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                             @PathVariable UUID userId) {
        teamService.removeMember(CurrentUser.id(jwt), workspaceId, userId);
    }

    @PostMapping("/workspaces/{workspaceId}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId) {
        teamService.leave(CurrentUser.id(jwt), workspaceId);
    }

    @GetMapping("/workspaces/{workspaceId}/invitations")
    public List<InvitationResponse> invitations(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId) {
        return teamService.invitations(CurrentUser.id(jwt), workspaceId);
    }

    @PostMapping("/workspaces/{workspaceId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public InvitationResponse invite(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                                     @Valid @RequestBody InviteRequest request) {
        return teamService.invite(CurrentUser.id(jwt), workspaceId, request.email(), request.role());
    }

    @GetMapping("/workspaces/{workspaceId}/invite-link")
    public InviteLinkResponse inviteLink(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId) {
        return teamService.inviteLink(CurrentUser.id(jwt), workspaceId);
    }

    @PutMapping("/workspaces/{workspaceId}/invite-link")
    public InviteLinkResponse resetInviteLink(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId) {
        return teamService.resetInviteLink(CurrentUser.id(jwt), workspaceId);
    }

    @DeleteMapping("/workspaces/{workspaceId}/invite-link")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disableInviteLink(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId) {
        teamService.disableInviteLink(CurrentUser.id(jwt), workspaceId);
    }

    @GetMapping("/workspaces/{workspaceId}/projects")
    public List<TeamProjectResponse> projects(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId) {
        return teamService.projects(CurrentUser.id(jwt), workspaceId);
    }

    @GetMapping("/workspaces/{workspaceId}/folders")
    public List<FolderResponse> folders(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId) {
        return teamService.folders(CurrentUser.id(jwt), workspaceId);
    }

    @PostMapping("/workspaces/{workspaceId}/folders")
    @ResponseStatus(HttpStatus.CREATED)
    public FolderResponse createFolder(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                                       @Valid @RequestBody FolderRequest request) {
        return teamService.createFolder(CurrentUser.id(jwt), workspaceId, request.name());
    }

    @PatchMapping("/folders/{folderId}")
    public FolderResponse renameFolder(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID folderId,
                                       @Valid @RequestBody FolderRequest request) {
        return teamService.renameFolder(CurrentUser.id(jwt), folderId, request.name());
    }

    @DeleteMapping("/folders/{folderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFolder(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID folderId) {
        teamService.deleteFolder(CurrentUser.id(jwt), folderId);
    }
}
