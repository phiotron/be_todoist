package com.taskly.repository;

import com.taskly.entity.WorkspaceMember;
import com.taskly.entity.WorkspaceRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, UUID> {

    boolean existsByWorkspaceIdAndUserId(UUID workspaceId, UUID userId);

    @Query("select m.workspace.id from WorkspaceMember m where m.user.id = :userId")
    List<UUID> findWorkspaceIdsByUserId(UUID userId);

    // ---- M8a

    Optional<WorkspaceMember> findByWorkspaceIdAndUserId(UUID workspaceId, UUID userId);

    @Query("select m from WorkspaceMember m join fetch m.user where m.workspace.id = :workspaceId "
            + "order by m.createdAt, m.id")
    List<WorkspaceMember> findAllWithUser(@Param("workspaceId") UUID workspaceId);

    @Query("select m from WorkspaceMember m join fetch m.workspace where m.user.id = :userId "
            + "order by m.createdAt, m.id")
    List<WorkspaceMember> findAllOfUser(@Param("userId") UUID userId);

    /** Người thừa kế khi chủ xoá tài khoản: Admin trước, rồi người vào sớm nhất (không nạp entity). */
    @Query(value = "select user_id from workspace_members where workspace_id = :workspaceId and user_id <> :ownerId "
            + "order by (role <> 'ADMIN'), created_at, id limit 1", nativeQuery = true)
    Optional<UUID> findHeir(@Param("workspaceId") UUID workspaceId, @Param("ownerId") UUID ownerId);

    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "update workspace_members set role = 'ADMIN' where workspace_id = :workspaceId "
            + "and user_id = :userId", nativeQuery = true)
    int promoteToAdmin(@Param("workspaceId") UUID workspaceId, @Param("userId") UUID userId);

    long countByWorkspaceId(UUID workspaceId);

    long countByWorkspaceIdAndRole(UUID workspaceId, WorkspaceRole role);
}
