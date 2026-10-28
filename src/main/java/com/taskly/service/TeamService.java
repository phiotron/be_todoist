package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.common.error.ProblemCodes;
import com.taskly.dto.sharing.InvitationResponse;
import com.taskly.dto.sharing.InviteLinkResponse;
import com.taskly.dto.workspace.FolderResponse;
import com.taskly.dto.workspace.TeamProjectResponse;
import com.taskly.dto.workspace.WorkspaceMemberResponse;
import com.taskly.dto.workspace.WorkspaceResponse;
import com.taskly.entity.FavoriteTargetType;
import com.taskly.entity.Folder;
import com.taskly.entity.Project;
import com.taskly.entity.ProjectAccess;
import com.taskly.entity.User;
import com.taskly.entity.Workspace;
import com.taskly.entity.WorkspaceMember;
import com.taskly.entity.WorkspaceRole;
import com.taskly.entity.WorkspaceType;
import com.taskly.repository.FolderRepository;
import com.taskly.repository.ProjectMemberRepository;
import com.taskly.repository.ProjectRepository;
import com.taskly.repository.WorkspaceMemberRepository;
import com.taskly.repository.WorkspaceRepository;
import com.taskly.security.AccessPolicy;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M8a workspace nhóm (plan M8a F6, F7): tạo / đổi tên / xoá, People, vai trò, lời mời + link workspace, folder, Team
 * Discovery. Workspace cá nhân chỉ đọc được (mọi thao tác ghi → 403 personal-workspace-locked).
 */
@Service
@Transactional
public class TeamService {

    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository workspaceMembers;
    private final ProjectRepository projects;
    private final ProjectMemberRepository projectMembers;
    private final FolderRepository folders;
    private final MembershipService membership;
    private final InvitationService invitationService;
    private final FavoriteService favoriteService;
    private final UserService userService;
    private final AccessPolicy accessPolicy;
    private final UsageLimits usageLimits;
    private final ApplicationEventPublisher events;

    public TeamService(WorkspaceRepository workspaces, WorkspaceMemberRepository workspaceMembers,
                       ProjectRepository projects, ProjectMemberRepository projectMembers, FolderRepository folders,
                       MembershipService membership, InvitationService invitationService,
                       FavoriteService favoriteService, UserService userService, AccessPolicy accessPolicy,
                       UsageLimits usageLimits, ApplicationEventPublisher events) {
        this.workspaces = workspaces;
        this.workspaceMembers = workspaceMembers;
        this.projects = projects;
        this.projectMembers = projectMembers;
        this.folders = folders;
        this.membership = membership;
        this.invitationService = invitationService;
        this.favoriteService = favoriteService;
        this.userService = userService;
        this.accessPolicy = accessPolicy;
        this.usageLimits = usageLimits;
        this.events = events;
    }

    // ---- Workspace ----

    @Transactional(readOnly = true)
    public List<WorkspaceResponse> list(UUID userId) {
        return workspaceMembers.findAllOfUser(userId).stream()
                .map(member -> toResponse(member.getWorkspace(), member.getRole()))
                .toList();
    }

    public WorkspaceResponse create(UUID userId, String name) {
        User owner = userService.requireUser(userId);
        Workspace workspace = workspaces.save(new Workspace(name, WorkspaceType.TEAM, owner));
        workspaceMembers.save(new WorkspaceMember(workspace, owner, WorkspaceRole.ADMIN));
        return toResponse(workspace, WorkspaceRole.ADMIN);
    }

    @Transactional(readOnly = true)
    public WorkspaceResponse get(UUID userId, UUID workspaceId) {
        WorkspaceRole role = accessPolicy.requireWorkspaceRole(userId, workspaceId);
        return toResponse(requireWorkspace(workspaceId), role);
    }

    public WorkspaceResponse rename(UUID userId, UUID workspaceId, String name) {
        Workspace workspace = requireTeamAdmin(userId, workspaceId);
        workspace.rename(name);
        workspaces.flush();
        return toResponse(workspace, WorkspaceRole.ADMIN);
    }

    /** Xoá workspace nhóm + mọi dự án / task (DB cascade); gỡ yêu thích và báo ProjectsDeletedEvent như xoá dự án. */
    public void delete(UUID userId, UUID workspaceId) {
        Workspace workspace = requireTeamAdmin(userId, workspaceId);
        usageLimits.lockWorkspace(workspaceId);
        List<UUID> projectIds = projects.findTreeRows(workspaceId).stream().map(row -> (UUID) row[0]).toList();
        usageLimits.lockProjects(projectIds);
        favoriteService.removeAllFor(FavoriteTargetType.PROJECT, projectIds);
        events.publishEvent(new ProjectsDeletedEvent(projectIds));
        workspaces.delete(workspace);
        workspaces.flush();
    }

    // ---- People ----

    @Transactional(readOnly = true)
    public List<WorkspaceMemberResponse> members(UUID userId, UUID workspaceId) {
        accessPolicy.requireWorkspaceMember(userId, workspaceId); // Guest → 403
        return workspaceMembers.findAllWithUser(workspaceId).stream()
                .map(m -> new WorkspaceMemberResponse(m.getUser().getId(), m.getUser().getEmail(),
                        m.getUser().getDisplayName(), m.getRole(), m.getCreatedAt()))
                .toList();
    }

    /** Đổi vai trò (chỉ Admin). Hạ Admin cuối cùng → 409 last-admin. */
    public List<WorkspaceMemberResponse> changeRole(UUID userId, UUID workspaceId, UUID memberId,
                                                    WorkspaceRole role) {
        requireTeamAdmin(userId, workspaceId);
        usageLimits.lockWorkspace(workspaceId);
        WorkspaceMember member = requireMember(workspaceId, memberId);
        if (member.getRole() == WorkspaceRole.ADMIN && role != WorkspaceRole.ADMIN) {
            requireAnotherAdmin(workspaceId);
        }
        member.changeRole(role);
        workspaceMembers.flush();
        return members(userId, workspaceId);
    }

    /** Admin xoá người (chính mình = rời). Xoá Admin cuối cùng → 409; xoá chủ (owner_id) → chủ chuyển cho người xoá. */
    public void removeMember(UUID userId, UUID workspaceId, UUID memberId) {
        if (memberId.equals(userId)) {
            leave(userId, workspaceId);
            return;
        }
        Workspace workspace = requireTeamAdmin(userId, workspaceId);
        usageLimits.lockWorkspace(workspaceId);
        WorkspaceMember member = requireMember(workspaceId, memberId);
        if (member.getRole() == WorkspaceRole.ADMIN) {
            requireAnotherAdmin(workspaceId);
        }
        if (workspace.getOwner().getId().equals(memberId)) {
            workspace.transferOwnership(userService.requireUser(userId));
        }
        membership.removeFromWorkspace(workspace, memberId);
    }

    /** POST workspaces/{id}/leave. Workspace cá nhân → 403; Admin cuối cùng → 409 last-admin. */
    public void leave(UUID userId, UUID workspaceId) {
        WorkspaceRole role = accessPolicy.requireWorkspaceRole(userId, workspaceId);
        Workspace workspace = requireTeam(workspaceId);
        usageLimits.lockWorkspace(workspaceId);
        if (role == WorkspaceRole.ADMIN) {
            requireAnotherAdmin(workspaceId);
        }
        if (workspace.getOwner().getId().equals(userId)) {
            workspaceMembers.findAllWithUser(workspaceId).stream()
                    .filter(m -> m.getRole() == WorkspaceRole.ADMIN && !m.getUser().getId().equals(userId))
                    .findFirst()
                    .ifPresent(m -> workspace.transferOwnership(m.getUser()));
        }
        membership.removeFromWorkspace(workspace, userId);
    }

    // ---- Lời mời + link ----

    public InvitationResponse invite(UUID userId, UUID workspaceId, String email, WorkspaceRole role) {
        Workspace workspace = requireTeamAdmin(userId, workspaceId);
        return invitationService.inviteToWorkspace(userId, workspace, email, role);
    }

    @Transactional(readOnly = true)
    public List<InvitationResponse> invitations(UUID userId, UUID workspaceId) {
        return invitationService.pendingForWorkspace(requireTeamAdmin(userId, workspaceId));
    }

    @Transactional(readOnly = true)
    public InviteLinkResponse inviteLink(UUID userId, UUID workspaceId) {
        return invitationService.link(requireTeamAdmin(userId, workspaceId).getInviteLinkToken());
    }

    public InviteLinkResponse resetInviteLink(UUID userId, UUID workspaceId) {
        Workspace workspace = requireTeamAdmin(userId, workspaceId);
        workspace.setInviteLinkToken(invitationService.newLinkToken());
        workspaces.flush();
        return invitationService.link(workspace.getInviteLinkToken());
    }

    public void disableInviteLink(UUID userId, UUID workspaceId) {
        requireTeamAdmin(userId, workspaceId).setInviteLinkToken(null);
    }

    // ---- Folder ----

    @Transactional(readOnly = true)
    public List<FolderResponse> folders(UUID userId, UUID workspaceId) {
        accessPolicy.requireWorkspaceMember(userId, workspaceId);
        return folders.findAllInWorkspace(workspaceId).stream().map(TeamService::toResponse).toList();
    }

    public FolderResponse createFolder(UUID userId, UUID workspaceId, String name) {
        accessPolicy.requireWorkspaceMember(userId, workspaceId);
        requireTeam(workspaceId);
        Folder folder = folders.save(new Folder(workspaceId, name, folders.maxSortOrder(workspaceId) + 1024));
        return toResponse(folder);
    }

    public FolderResponse renameFolder(UUID userId, UUID folderId, String name) {
        Folder folder = requireFolder(userId, folderId);
        folder.rename(name);
        folders.flush();
        return toResponse(folder);
    }

    /** Xoá folder: dự án trong folder giữ nguyên, folder_id → null (ON DELETE SET NULL). */
    public void deleteFolder(UUID userId, UUID folderId) {
        folders.delete(requireFolder(userId, folderId));
        folders.flush();
    }

    // ---- Team Discovery ----

    /** Dự án chưa lưu trữ người gọi thấy được (Team, hoặc đã tham gia); Guest → 403 (không thấy danh sách dự án Team). */
    @Transactional(readOnly = true)
    public List<TeamProjectResponse> projects(UUID userId, UUID workspaceId) {
        accessPolicy.requireWorkspaceMember(userId, workspaceId);
        requireTeam(workspaceId);
        List<Project> active = projects.findActiveInWorkspace(workspaceId);
        List<UUID> ids = active.stream().map(Project::getId).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Set<UUID> joined = new HashSet<>(projectMembers.findJoinedAmong(userId, ids));
        Map<UUID, Long> memberCounts = projectMembers.countByProjectIds(ids).stream()
                .collect(Collectors.toMap(ProjectMemberRepository.MemberCount::getProjectId,
                        ProjectMemberRepository.MemberCount::getMemberCount));
        Map<UUID, Long> taskCounts = projects.countOpenTasks(ids).stream()
                .collect(Collectors.toMap(ProjectRepository.ProjectTaskCount::getProjectId,
                        ProjectRepository.ProjectTaskCount::getTaskCount));
        return active.stream()
                .filter(p -> joined.contains(p.getId()) || p.getAccess() == ProjectAccess.TEAM)
                .map(p -> new TeamProjectResponse(p.getId(), p.getName(), p.getColor(), p.getFolderId(),
                        p.getAccess(), memberCounts.getOrDefault(p.getId(), 0L),
                        taskCounts.getOrDefault(p.getId(), 0L), joined.contains(p.getId())))
                .toList();
    }

    // ---- nội bộ ----

    private Workspace requireWorkspace(UUID workspaceId) {
        return workspaces.findById(workspaceId)
                .orElseThrow(() -> ApiException.notFound(ErrorMessage.WORKSPACE_NOT_FOUND));
    }

    /** Workspace nhóm; workspace cá nhân → 403 personal-workspace-locked. */
    private Workspace requireTeam(UUID workspaceId) {
        Workspace workspace = requireWorkspace(workspaceId);
        if (!workspace.isTeam()) {
            throw ApiException.forbidden(ErrorMessage.PERSONAL_WORKSPACE_LOCKED);
        }
        return workspace;
    }

    /** Người ngoài 404 → workspace cá nhân 403 → không phải Admin 403. */
    private Workspace requireTeamAdmin(UUID userId, UUID workspaceId) {
        accessPolicy.requireWorkspaceRole(userId, workspaceId);
        Workspace workspace = requireTeam(workspaceId);
        accessPolicy.requireWorkspaceAdmin(userId, workspaceId);
        return workspace;
    }

    private WorkspaceMember requireMember(UUID workspaceId, UUID memberId) {
        return workspaceMembers.findByWorkspaceIdAndUserId(workspaceId, memberId)
                .orElseThrow(() -> ApiException.notFound(ErrorMessage.MEMBER_NOT_FOUND));
    }

    private void requireAnotherAdmin(UUID workspaceId) {
        if (workspaceMembers.countByWorkspaceIdAndRole(workspaceId, WorkspaceRole.ADMIN) <= 1) {
            throw ApiException.conflict(ErrorMessage.LAST_ADMIN).withCode(ProblemCodes.LAST_ADMIN);
        }
    }

    /** Folder mà người gọi (Admin / Member) quản lý được; không thấy → 404, Guest → 403. */
    private Folder requireFolder(UUID userId, UUID folderId) {
        Folder folder = folders.findById(folderId)
                .orElseThrow(() -> ApiException.notFound(ErrorMessage.FOLDER_NOT_FOUND));
        if (!workspaceMembers.existsByWorkspaceIdAndUserId(folder.getWorkspaceId(), userId)) {
            throw ApiException.notFound(ErrorMessage.FOLDER_NOT_FOUND);
        }
        accessPolicy.requireWorkspaceMember(userId, folder.getWorkspaceId());
        return folder;
    }

    private WorkspaceResponse toResponse(Workspace workspace, WorkspaceRole role) {
        return new WorkspaceResponse(workspace.getId(), workspace.getName(), workspace.getType(), role,
                workspaceMembers.countByWorkspaceId(workspace.getId()));
    }

    private static FolderResponse toResponse(Folder folder) {
        return new FolderResponse(folder.getId(), folder.getWorkspaceId(), folder.getName(), folder.getSortOrder());
    }
}
