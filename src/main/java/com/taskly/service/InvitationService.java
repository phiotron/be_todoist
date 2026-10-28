package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.common.error.ProblemCodes;
import com.taskly.dto.sharing.AcceptInvitationResponse;
import com.taskly.dto.sharing.InvitationResponse;
import com.taskly.dto.sharing.InviteLinkResponse;
import com.taskly.entity.Invitation;
import com.taskly.entity.LimitKind;
import com.taskly.entity.Project;
import com.taskly.entity.User;
import com.taskly.entity.Workspace;
import com.taskly.entity.WorkspaceRole;
import com.taskly.integration.MailDeliveryException;
import com.taskly.integration.MailSender;
import com.taskly.integration.OutgoingMail;
import com.taskly.repository.InvitationRepository;
import com.taskly.repository.ProjectRepository;
import com.taskly.repository.WorkspaceMemberRepository;
import com.taskly.repository.WorkspaceRepository;
import com.taskly.security.AccessPolicy;
import com.taskly.security.OpaqueTokens;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M8a lời mời (ADR 0003, plan M8a F2–F4). Sở hữu bảng invitations + cột invite_link_token của projects / workspaces.
 * Thư mời: hạn 7 ngày, dùng một lần, bắt buộc trùng email, DB chỉ lưu SHA-256. Link: dùng nhiều lần, không hết hạn, lưu
 * nguyên để chép lại. Không log email / token. Gửi thư lỗi → 503 và rollback (lời mời không được tạo).
 */
@Service
@Transactional
public class InvitationService {

    private static final Logger log = LoggerFactory.getLogger(InvitationService.class);

    static final String KIND_PROJECT = "PROJECT";
    static final String KIND_WORKSPACE = "WORKSPACE";

    private final InvitationRepository invitations;
    private final ProjectRepository projects;
    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository workspaceMembers;
    private final MembershipService membership;
    private final UserService userService;
    private final AccessPolicy accessPolicy;
    private final UsageLimits usageLimits;
    private final MailSender mailSender;
    private final MessageSource messageSource;
    private final Clock clock;
    private final int ttlDays;
    private final String linkBase;

    public InvitationService(InvitationRepository invitations, ProjectRepository projects,
                             WorkspaceRepository workspaces, WorkspaceMemberRepository workspaceMembers,
                             MembershipService membership, UserService userService, AccessPolicy accessPolicy,
                             UsageLimits usageLimits, MailSender mailSender, MessageSource messageSource, Clock clock,
                             @Value("${taskly.invitations.ttl-days:7}") int ttlDays,
                             @Value("${taskly.invitations.link-base:taskly://join/}") String linkBase) {
        this.invitations = invitations;
        this.projects = projects;
        this.workspaces = workspaces;
        this.workspaceMembers = workspaceMembers;
        this.membership = membership;
        this.userService = userService;
        this.accessPolicy = accessPolicy;
        this.usageLimits = usageLimits;
        this.mailSender = mailSender;
        this.messageSource = messageSource;
        this.clock = clock;
        this.ttlDays = ttlDays;
        this.linkBase = linkBase;
    }

    // ---- Tạo lời mời email ----

    /**
     * Mời vào dự án (caller đã kiểm quyền mời). Thứ tự: tự mời → 400; khoá P; đã trong dự án → 409 already-member; dự
     * án cá nhân vượt 5 chỗ (không tính lời mời cũ của chính email này) → 422; thay lời mời cũ; auto accept nếu người
     * nhận bật; gửi thư.
     */
    public InvitationResponse inviteToProject(UUID inviterId, Project project, String email) {
        User inviter = userService.requireUser(inviterId);
        rejectSelf(inviter, email);
        usageLimits.lockProject(project.getId());
        Optional<User> invitee = userService.findByEmail(email);
        if (invitee.isPresent() && membership.isInProject(project, invitee.get().getId())) {
            throw alreadyMember();
        }
        invitations.deleteOpenForProject(project.getId(), email);
        invitations.flush();
        if (!project.getWorkspace().isTeam()) {
            usageLimits.check(LimitKind.PROJECT_MEMBERS, membership.reservedSeats(project), 1);
        }
        String token = OpaqueTokens.generate();
        Invitation invitation = invitations.save(Invitation.toProject(project.getId(), email,
                OpaqueTokens.sha256Hex(token), inviterId, expiry()));
        boolean autoAccepted = invitee.filter(User::isAutoAcceptInvites).isPresent();
        if (autoAccepted) {
            invitation.markAccepted(clock.instant());
            invitations.flush();
            membership.addToProject(project, invitee.get(), false);
        }
        send(inviter, invitee, email, project.getName(), KIND_PROJECT, token, autoAccepted);
        return toResponse(invitation, project.getName(), inviter);
    }

    /** Mời vào workspace nhóm với vai trò ADMIN | MEMBER (caller đã kiểm Admin). */
    public InvitationResponse inviteToWorkspace(UUID inviterId, Workspace workspace, String email, WorkspaceRole role) {
        WorkspaceRole effective = role == null ? WorkspaceRole.MEMBER : role;
        if (effective == WorkspaceRole.GUEST) {
            throw ApiException.badRequest(ErrorMessage.INVITATION_ROLE_INVALID);
        }
        User inviter = userService.requireUser(inviterId);
        rejectSelf(inviter, email);
        Optional<User> invitee = userService.findByEmail(email);
        if (invitee.isPresent() && workspaceMembers.findByWorkspaceIdAndUserId(workspace.getId(),
                invitee.get().getId()).filter(m -> m.getRole() != WorkspaceRole.GUEST).isPresent()) {
            throw alreadyMember();
        }
        invitations.deleteOpenForWorkspace(workspace.getId(), email);
        invitations.flush();
        String token = OpaqueTokens.generate();
        Invitation invitation = invitations.save(Invitation.toWorkspace(workspace.getId(), email, effective,
                OpaqueTokens.sha256Hex(token), inviterId, expiry()));
        boolean autoAccepted = invitee.filter(User::isAutoAcceptInvites).isPresent();
        if (autoAccepted) {
            invitation.markAccepted(clock.instant());
            invitations.flush();
            joinWorkspace(workspace, invitee.get(), effective);
        }
        send(inviter, invitee, email, workspace.getName(), KIND_WORKSPACE, token, autoAccepted);
        return toResponse(invitation, workspace.getName(), inviter);
    }

    // ---- Danh sách ----

    @Transactional(readOnly = true)
    public List<InvitationResponse> pendingForProject(Project project) {
        return invitations.findPendingForProject(project.getId(), clock.instant()).stream()
                .map(i -> toResponse(i, project.getName(), userService.requireUser(i.getInvitedBy())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<InvitationResponse> pendingForWorkspace(Workspace workspace) {
        return invitations.findPendingForWorkspace(workspace.getId(), clock.instant()).stream()
                .map(i -> toResponse(i, workspace.getName(), userService.requireUser(i.getInvitedBy())))
                .toList();
    }

    /** GET invitations: lời mời đang chờ gửi tới email của người gọi. */
    @Transactional(readOnly = true)
    public List<InvitationResponse> mine(UUID userId) {
        User user = userService.requireUser(userId);
        return invitations.findPendingForEmail(user.getEmail(), clock.instant()).stream()
                .map(this::toResponse)
                .toList();
    }

    // ---- Chấp nhận / từ chối / thu hồi ----

    /**
     * POST invitations/accept {token}: token thư mời (hash khớp) hoặc token link dự án / workspace. Không khớp → 404;
     * thư mời hết hạn / đã dùng / đã từ chối → 410 invitation-expired; email khác → 403.
     */
    public AcceptInvitationResponse acceptToken(UUID userId, String token) {
        User user = userService.requireUser(userId);
        Optional<Invitation> invitation = invitations.findByTokenHashForUpdate(OpaqueTokens.sha256Hex(token));
        if (invitation.isPresent()) {
            return accept(user, invitation.get());
        }
        Optional<Project> project = projects.findByInviteLinkToken(token);
        if (project.isPresent()) {
            Project target = project.get();
            if (target.isArchived()) {
                throw expired();
            }
            membership.addToProject(target, user, true);
            return new AcceptInvitationResponse(KIND_PROJECT, target.getId(), target.getWorkspace().getId());
        }
        Workspace workspace = workspaces.findByInviteLinkToken(token)
                .orElseThrow(() -> ApiException.notFound(ErrorMessage.INVITATION_NOT_FOUND));
        joinWorkspace(workspace, user, WorkspaceRole.MEMBER);
        return new AcceptInvitationResponse(KIND_WORKSPACE, null, workspace.getId());
    }

    /** POST invitations/{id}/accept — lời mời phải gửi tới email của người gọi (khác → 404, không lộ). */
    public AcceptInvitationResponse acceptById(UUID userId, UUID invitationId) {
        User user = userService.requireUser(userId);
        Invitation invitation = invitations.findByIdForUpdate(invitationId)
                .filter(i -> i.getEmail().equals(user.getEmail()))
                .orElseThrow(() -> ApiException.notFound(ErrorMessage.INVITATION_NOT_FOUND));
        return accept(user, invitation);
    }

    public void decline(UUID userId, UUID invitationId) {
        User user = userService.requireUser(userId);
        Invitation invitation = invitations.findByIdForUpdate(invitationId)
                .filter(i -> i.getEmail().equals(user.getEmail()))
                .orElseThrow(() -> ApiException.notFound(ErrorMessage.INVITATION_NOT_FOUND));
        if (!invitation.isPending(clock.instant())) {
            throw expired();
        }
        invitation.markDeclined(clock.instant());
    }

    /**
     * DELETE invitations/{id}: thu hồi lời mời đang chờ. Dự án: người sửa được dự án (không phải Guest); workspace:
     * Admin. Người không thấy đích → 404.
     */
    public void revoke(UUID userId, UUID invitationId) {
        Invitation invitation = invitations.findById(invitationId)
                .orElseThrow(() -> ApiException.notFound(ErrorMessage.INVITATION_NOT_FOUND));
        if (invitation.getProjectId() != null) {
            Project project = projects.findById(invitation.getProjectId())
                    .orElseThrow(() -> ApiException.notFound(ErrorMessage.INVITATION_NOT_FOUND));
            requireCanInvite(userId, project, ErrorMessage.INVITATION_NOT_FOUND);
        } else {
            if (!workspaceMembers.existsByWorkspaceIdAndUserId(invitation.getWorkspaceId(), userId)) {
                throw ApiException.notFound(ErrorMessage.INVITATION_NOT_FOUND);
            }
            accessPolicy.requireWorkspaceAdmin(userId, invitation.getWorkspaceId());
        }
        invitations.delete(invitation);
    }

    /**
     * Quyền mời vào dự án (cũng là quyền xem / thu hồi lời mời, quản lý link): sửa được dự án (404 / 403 xem trước) và
     * không phải Guest của team (403).
     */
    public AccessPolicy.ProjectAccessInfo requireCanInvite(UUID userId, Project project, ErrorMessage notFound) {
        AccessPolicy.ProjectAccessInfo info = accessPolicy.checkProjectWrite(userId, project, notFound);
        if (info.isTeam() && info.workspaceRole() == WorkspaceRole.GUEST) {
            throw ApiException.forbidden(ErrorMessage.GUEST_NOT_ALLOWED);
        }
        return info;
    }

    // ---- Link mời ----

    public InviteLinkResponse link(String token) {
        return token == null ? new InviteLinkResponse(null, null) : new InviteLinkResponse(token, linkBase + token);
    }

    /** Token link mới (đặt lại = link cũ hết tác dụng). */
    public String newLinkToken() {
        return OpaqueTokens.generate();
    }

    // ---- nội bộ ----

    private AcceptInvitationResponse accept(User user, Invitation invitation) {
        if (!invitation.isPending(clock.instant())) {
            throw expired();
        }
        if (!invitation.getEmail().equals(user.getEmail())) {
            throw ApiException.forbidden(ErrorMessage.INVITATION_EMAIL_MISMATCH);
        }
        invitation.markAccepted(clock.instant());
        invitations.flush();
        if (invitation.getProjectId() != null) {
            Project project = projects.findById(invitation.getProjectId())
                    .orElseThrow(() -> ApiException.notFound(ErrorMessage.INVITATION_NOT_FOUND));
            membership.addToProject(project, user, false); // chỗ đã giữ lúc mời
            return new AcceptInvitationResponse(KIND_PROJECT, project.getId(), project.getWorkspace().getId());
        }
        Workspace workspace = workspaces.findById(invitation.getWorkspaceId())
                .orElseThrow(() -> ApiException.notFound(ErrorMessage.INVITATION_NOT_FOUND));
        joinWorkspace(workspace, user, invitation.getRole());
        return new AcceptInvitationResponse(KIND_WORKSPACE, null, workspace.getId());
    }

    /** Vào workspace: chưa là thành viên → thêm; đang là GUEST và được mời làm MEMBER / ADMIN → nâng vai trò. */
    private void joinWorkspace(Workspace workspace, User user, WorkspaceRole role) {
        workspaceMembers.findByWorkspaceIdAndUserId(workspace.getId(), user.getId()).ifPresentOrElse(member -> {
            if (member.getRole() == WorkspaceRole.GUEST && role != WorkspaceRole.GUEST) {
                member.changeRole(role);
            }
        }, () -> membership.addToWorkspace(workspace, user, role));
    }

    private Instant expiry() {
        return clock.instant().plus(Duration.ofDays(ttlDays));
    }

    private static void rejectSelf(User inviter, String email) {
        if (inviter.getEmail().equals(email)) {
            throw ApiException.badRequest(ErrorMessage.INVITE_SELF);
        }
    }

    private static ApiException alreadyMember() {
        return ApiException.conflict(ErrorMessage.ALREADY_MEMBER).withCode(ProblemCodes.ALREADY_MEMBER);
    }

    private static ApiException expired() {
        return ApiException.gone(ErrorMessage.INVITATION_EXPIRED).withCode(ProblemCodes.INVITATION_EXPIRED);
    }

    /** Thư theo ngôn ngữ người nhận (chưa có tài khoản → ngôn ngữ người mời). Lỗi gửi → 503 + rollback. */
    private void send(User inviter, Optional<User> invitee, String email, String targetName, String kind,
                      String token, boolean autoAccepted) {
        Locale locale = Locale.forLanguageTag(invitee.map(User::getLocale).orElse(inviter.getLocale()));
        String subjectKey = KIND_PROJECT.equals(kind)
                ? "mail.invitation.project.subject" : "mail.invitation.workspace.subject";
        String body = autoAccepted
                ? messageSource.getMessage("mail.invitation.added.body",
                        new Object[] {inviter.getDisplayName(), targetName}, locale)
                : messageSource.getMessage("mail.invitation.body", new Object[] {inviter.getDisplayName(), targetName,
                        linkBase + token, token, String.valueOf(ttlDays)}, locale);
        try {
            mailSender.send(new OutgoingMail(email, messageSource.getMessage(subjectKey, null, locale), body));
        } catch (MailDeliveryException e) {
            log.warn("Không gửi được thư mời; trả 503 và huỷ lời mời vừa tạo");
            throw ApiException.serviceUnavailable(ErrorMessage.MAIL_UNAVAILABLE);
        }
    }

    private InvitationResponse toResponse(Invitation invitation) {
        String targetName = invitation.getProjectId() != null
                ? projects.findById(invitation.getProjectId()).map(Project::getName).orElse("")
                : workspaces.findById(invitation.getWorkspaceId()).map(Workspace::getName).orElse("");
        return toResponse(invitation, targetName, userService.requireUser(invitation.getInvitedBy()));
    }

    private InvitationResponse toResponse(Invitation invitation, String targetName, User inviter) {
        return new InvitationResponse(invitation.getId(),
                invitation.getProjectId() != null ? KIND_PROJECT : KIND_WORKSPACE,
                invitation.getProjectId(), invitation.getWorkspaceId(), targetName, invitation.getEmail(),
                invitation.getRole(), inviter.getDisplayName(), invitation.getExpiresAt(), invitation.getCreatedAt(),
                invitation.getAcceptedAt() != null);
    }
}
