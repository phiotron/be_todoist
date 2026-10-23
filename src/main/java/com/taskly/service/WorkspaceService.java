package com.taskly.service;

import com.taskly.entity.User;
import com.taskly.entity.Workspace;
import com.taskly.entity.WorkspaceMember;
import com.taskly.entity.WorkspaceRole;
import com.taskly.entity.WorkspaceType;
import com.taskly.repository.WorkspaceMemberRepository;
import com.taskly.repository.WorkspaceRepository;
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
}
