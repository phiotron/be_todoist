package com.taskly.repository;

import com.taskly.entity.WorkspaceMember;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, UUID> {

    boolean existsByWorkspaceIdAndUserId(UUID workspaceId, UUID userId);

    @Query("select m.workspace.id from WorkspaceMember m where m.user.id = :userId")
    List<UUID> findWorkspaceIdsByUserId(UUID userId);
}
