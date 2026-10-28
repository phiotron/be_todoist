package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.common.error.ProblemCodes;
import com.taskly.dto.project.ProjectResponse;
import com.taskly.dto.sharing.InvitationResponse;
import com.taskly.dto.sharing.InviteLinkResponse;
import com.taskly.dto.sharing.ProjectMemberResponse;
import com.taskly.entity.Project;
import com.taskly.entity.ProjectAccess;
import com.taskly.entity.User;
import com.taskly.mapper.UserMapper;
import com.taskly.repository.ProjectMemberRepository;
import com.taskly.repository.ProjectRepository;
import com.taskly.security.AccessPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M8a chia sẻ phía DỰ ÁN (plan M8a F2, F5, F7): "In this project", mời / thu hồi, link mời, rời / xoá người, tham gia
 * dự án Team. Mọi kiểm quyền qua AccessPolicy (404 không thấy, 403 thiếu quyền).
 */
@Service
@Transactional
public class SharingService {

    private final ProjectRepository projects;
    private final ProjectMemberRepository projectMembers;
    private final ProjectService projectService;
    private final InvitationService invitationService;
    private final MembershipService membership;
    private final UserService userService;
    private final AccessPolicy accessPolicy;
    private final com.taskly.service.UsageLimits usageLimits;

    public SharingService(ProjectRepository projects, ProjectMemberRepository projectMembers,
                          ProjectService projectService, InvitationService invitationService,
                          MembershipService membership, UserService userService, AccessPolicy accessPolicy,
                          UsageLimits usageLimits) {
        this.projects = projects;
        this.projectMembers = projectMembers;
        this.projectService = projectService;
        this.invitationService = invitationService;
        this.membership = membership;
        this.userService = userService;
        this.accessPolicy = accessPolicy;
        this.usageLimits = usageLimits;
    }

    /** GET projects/{id}/members — chủ dự án cá nhân đứng đầu (isOwner), rồi theo thời điểm vào. */
    @Transactional(readOnly = true)
    public List<ProjectMemberResponse> members(UUID userId, UUID projectId) {
        Project project = projectService.requireReadable(userId, projectId);
        List<ProjectMemberResponse> result = new ArrayList<>();
        boolean team = project.getWorkspace().isTeam();
        if (!team) {
            result.add(toMember(project.getWorkspace().getOwner(), true));
        }
        projectMembers.findAllByProjectIdOrderByCreatedAtAsc(projectId).forEach(member -> result.add(toMember(
                userService.requireUser(member.getUserId()),
                team && member.getUserId().equals(project.getCreatedBy()))));
        return result;
    }

    /** POST projects/{id}/invitations. Inbox → 403; dự án lưu trữ → 409 project-archived. */
    public InvitationResponse invite(UUID userId, UUID projectId, String email) {
        Project project = requireInvitable(userId, projectId);
        return invitationService.inviteToProject(userId, project, email);
    }

    @Transactional(readOnly = true)
    public List<InvitationResponse> invitations(UUID userId, UUID projectId) {
        Project project = projectService.requireReadable(userId, projectId);
        invitationService.requireCanInvite(userId, project, ErrorMessage.PROJECT_NOT_FOUND);
        return invitationService.pendingForProject(project);
    }

    @Transactional(readOnly = true)
    public InviteLinkResponse inviteLink(UUID userId, UUID projectId) {
        Project project = projectService.requireReadable(userId, projectId);
        invitationService.requireCanInvite(userId, project, ErrorMessage.PROJECT_NOT_FOUND);
        return invitationService.link(project.getInviteLinkToken());
    }

    /** PUT projects/{id}/invite-link: bật / đặt lại link (token mới; link cũ hết tác dụng). */
    public InviteLinkResponse resetInviteLink(UUID userId, UUID projectId) {
        Project project = requireInvitable(userId, projectId);
        project.setInviteLinkToken(invitationService.newLinkToken());
        projects.flush();
        return invitationService.link(project.getInviteLinkToken());
    }

    /** DELETE projects/{id}/invite-link: tắt link. */
    public void disableInviteLink(UUID userId, UUID projectId) {
        Project project = projectService.requireReadable(userId, projectId);
        invitationService.requireCanInvite(userId, project, ErrorMessage.PROJECT_NOT_FOUND);
        project.setInviteLinkToken(null);
    }

    /**
     * POST projects/{id}/leave. Chủ dự án cá nhân → 403; chưa ở trong dự án (xem trước) → 404 member-not-found; người
     * cuối của dự án Restricted → 409 last-project-member.
     */
    public void leave(UUID userId, UUID projectId) {
        Project project = projectService.requireReadable(userId, projectId);
        AccessPolicy.ProjectAccessInfo info = accessPolicy.projectAccess(userId, project);
        if (info.owner()) {
            throw ApiException.forbidden(ErrorMessage.OWNER_CANNOT_LEAVE);
        }
        if (!info.joined()) {
            throw ApiException.notFound(ErrorMessage.MEMBER_NOT_FOUND);
        }
        usageLimits.lockProject(projectId);
        if (project.getAccess() == ProjectAccess.RESTRICTED && projectMembers.countByProjectId(projectId) <= 1) {
            throw ApiException.conflict(ErrorMessage.LAST_PROJECT_MEMBER).withCode(ProblemCodes.LAST_PROJECT_MEMBER);
        }
        membership.removeFromProject(project, userId);
    }

    /**
     * DELETE projects/{id}/members/{memberId}: chính mình = leave; người khác cần quyền quản lý dự án (chủ dự án cá
     * nhân / Admin / người tạo dự án nhóm) → 403; người không ở trong dự án → 404; chủ dự án cá nhân không bị xoá (403).
     */
    public void removeMember(UUID userId, UUID projectId, UUID memberId) {
        if (memberId.equals(userId)) {
            leave(userId, projectId);
            return;
        }
        Project project = projectService.requireReadable(userId, projectId);
        accessPolicy.checkProjectManage(userId, project);
        if (!project.getWorkspace().isTeam() && project.getWorkspace().getOwner().getId().equals(memberId)) {
            throw ApiException.forbidden(ErrorMessage.OWNER_CANNOT_LEAVE);
        }
        if (!projectMembers.existsByProjectIdAndUserId(projectId, memberId)) {
            throw ApiException.notFound(ErrorMessage.MEMBER_NOT_FOUND);
        }
        membership.removeFromProject(project, memberId);
    }

    /** POST projects/{id}/join — Admin / Member tham gia dự án Team (đã tham gia → 200 như cũ); không thấy → 404. */
    public ProjectResponse join(UUID userId, UUID projectId) {
        Project project = projectService.requireReadable(userId, projectId);
        AccessPolicy.ProjectAccessInfo info = accessPolicy.projectAccess(userId, project);
        if (info.role() == AccessPolicy.ProjectRole.PREVIEW) {
            membership.addToProject(project, userService.requireUser(userId), false);
        }
        return projectService.get(userId, projectId);
    }

    /** Quyền mời + dự án không phải Inbox (403) và chưa lưu trữ (409). */
    private Project requireInvitable(UUID userId, UUID projectId) {
        Project project = projectService.requireReadable(userId, projectId);
        invitationService.requireCanInvite(userId, project, ErrorMessage.PROJECT_NOT_FOUND);
        if (project.isInbox()) {
            throw ApiException.forbidden(ErrorMessage.INBOX_PROTECTED);
        }
        if (project.isArchived()) {
            throw ApiException.conflict(ErrorMessage.PROJECT_ARCHIVED).withCode(ProblemCodes.PROJECT_ARCHIVED);
        }
        return project;
    }

    private static ProjectMemberResponse toMember(User user, boolean owner) {
        return new ProjectMemberResponse(user.getId(), user.getEmail(), user.getDisplayName(), owner,
                UserMapper.avatarUrl(user.getId(), user.getAvatarUpdatedAt()));
    }
}
