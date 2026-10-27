package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.entity.User;
import com.taskly.entity.Workspace;
import com.taskly.entity.WorkspaceMember;
import com.taskly.entity.WorkspaceRole;
import com.taskly.entity.WorkspaceType;
import com.taskly.repository.WorkspaceMemberRepository;
import com.taskly.repository.WorkspaceRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sở hữu bảng workspaces và workspace_members. */
@Service
public class WorkspaceService {

    public static final String PERSONAL_WORKSPACE_NAME = "Cá nhân";

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;

    public WorkspaceService(WorkspaceRepository workspaceRepository,
                            WorkspaceMemberRepository workspaceMemberRepository) {
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
    }

    /**
     * Workspace PERSONAL cho user mới, user là chủ sở hữu và là ADMIN duy nhất.
     * Gọi từ AuthService.register → chạy chung transaction đăng ký (Propagation.REQUIRED).
     */
    @Transactional
    public Workspace createPersonalWorkspace(User owner) {
        Workspace workspace = workspaceRepository.save(
                new Workspace(PERSONAL_WORKSPACE_NAME, WorkspaceType.PERSONAL, owner));
        workspaceMemberRepository.save(new WorkspaceMember(workspace, owner, WorkspaceRole.ADMIN));
        return workspace;
    }

    /**
     * DELETE me (spec 10.2): workspaces.owner_id không cascade ở V1 nên phải xoá workspace của user TRƯỚC khi xoá user.
     * DB cascade phần còn lại (membership, dự án và dữ liệu bên dưới). M8 xử lý workspace nhóm (chốt ở plan M8).
     */
    @Transactional
    public int deleteOwnedWorkspaces(UUID ownerId) {
        return workspaceRepository.deleteAllByOwnerId(ownerId);
    }

    /** Workspace cá nhân của user (Mức 1: dự án mới luôn tạo ở đây). Không có → phiên không hợp lệ (401). */
    @Transactional(readOnly = true)
    public Workspace personalWorkspace(UUID userId) {
        return workspaceRepository.findByOwnerIdAndType(userId, WorkspaceType.PERSONAL)
                .orElseThrow(() -> ApiException.unauthorized(ErrorMessage.SESSION_INVALID));
    }
}
