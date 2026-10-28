package com.taskly.service;

import com.taskly.entity.FavoriteTargetType;
import com.taskly.entity.LimitKind;
import com.taskly.entity.Project;
import com.taskly.entity.ProjectMember;
import com.taskly.entity.User;
import com.taskly.entity.Workspace;
import com.taskly.entity.WorkspaceMember;
import com.taskly.entity.WorkspaceRole;
import com.taskly.repository.InvitationRepository;
import com.taskly.repository.ProjectMemberRepository;
import com.taskly.repository.WorkspaceMemberRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * M8a: thao tác ghi thành viên dùng chung cho lời mời (email / link), tham gia dự án Team, chuyển dự án vào team. Không
 * kiểm quyền người gọi (caller đã kiểm qua AccessPolicy) — chỉ giữ bất biến dữ liệu: người vào dự án nhóm phải là thành
 * viên workspace (chưa có → GUEST), giới hạn PROJECT_MEMBERS của dự án cá nhân.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class MembershipService {

    private final ProjectMemberRepository projectMembers;
    private final WorkspaceMemberRepository workspaceMembers;
    private final InvitationRepository invitations;
    private final FavoriteService favoriteService;
    private final UsageLimits usageLimits;
    private final Clock clock;

    public MembershipService(ProjectMemberRepository projectMembers, WorkspaceMemberRepository workspaceMembers,
                             InvitationRepository invitations, FavoriteService favoriteService,
                             UsageLimits usageLimits, Clock clock) {
        this.projectMembers = projectMembers;
        this.workspaceMembers = workspaceMembers;
        this.invitations = invitations;
        this.favoriteService = favoriteService;
        this.usageLimits = usageLimits;
        this.clock = clock;
    }

    /** Đã ở trong dự án: chủ workspace cá nhân hoặc có hàng project_members. */
    public boolean isInProject(Project project, UUID userId) {
        Workspace workspace = project.getWorkspace();
        if (!workspace.isTeam() && workspace.getOwner().getId().equals(userId)) {
            return true;
        }
        return projectMembers.existsByProjectIdAndUserId(project.getId(), userId);
    }

    /** Số người của dự án cá nhân đang "giữ chỗ": chủ + cộng tác viên + lời mời email còn hiệu lực. */
    public long reservedSeats(Project project) {
        return 1 + projectMembers.countByProjectId(project.getId())
                + invitations.countPendingForProject(project.getId(), clock.instant());
    }

    /**
     * Thêm user vào dự án (idempotent: đã ở trong → false). Dự án cá nhân: checkLimit = true khi chỗ chưa được giữ
     * trước (link mời, auto accept tính lời mời mới đã lưu) → khoá P rồi đếm (≤ PROJECT_MEMBERS). Dự án nhóm: user chưa
     * thuộc workspace → thêm làm GUEST.
     */
    public boolean addToProject(Project project, User user, boolean checkLimit) {
        if (isInProject(project, user.getId())) {
            return false;
        }
        Workspace workspace = project.getWorkspace();
        if (workspace.isTeam()) {
            addToWorkspace(workspace, user, WorkspaceRole.GUEST);
        } else if (checkLimit) {
            usageLimits.lockProject(project.getId());
            usageLimits.check(LimitKind.PROJECT_MEMBERS, reservedSeats(project), 1);
        }
        projectMembers.save(new ProjectMember(project.getId(), user.getId()));
        return true;
    }

    /** Thêm vào workspace nhóm (idempotent: đã là thành viên → giữ vai trò cũ, trả false). */
    public boolean addToWorkspace(Workspace workspace, User user, WorkspaceRole role) {
        if (workspaceMembers.existsByWorkspaceIdAndUserId(workspace.getId(), user.getId())) {
            return false;
        }
        workspaceMembers.save(new WorkspaceMember(workspace, user, role));
        return true;
    }

    /** Bỏ user khỏi dự án (hàng project_members + yêu thích của họ). Guest không còn dự án nào trong team → rời team. */
    public void removeFromProject(Project project, UUID userId) {
        projectMembers.findByProjectIdAndUserId(project.getId(), userId).ifPresent(projectMembers::delete);
        projectMembers.flush();
        favoriteService.remove(userId, FavoriteTargetType.PROJECT, project.getId());
        Workspace workspace = project.getWorkspace();
        if (workspace.isTeam()) {
            workspaceMembers.findByWorkspaceIdAndUserId(workspace.getId(), userId)
                    .filter(member -> member.getRole() == WorkspaceRole.GUEST)
                    .filter(member -> projectMembers.countInWorkspace(workspace.getId(), userId) == 0)
                    .ifPresent(workspaceMembers::delete);
        }
    }

    /** Bỏ user khỏi workspace nhóm và mọi dự án trong đó. */
    public void removeFromWorkspace(Workspace workspace, UUID userId) {
        projectMembers.deleteAllInWorkspace(workspace.getId(), userId);
        workspaceMembers.findByWorkspaceIdAndUserId(workspace.getId(), userId).ifPresent(workspaceMembers::delete);
        workspaceMembers.flush();
    }
}
