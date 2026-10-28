package com.taskly.repository;

import com.taskly.entity.ProjectMember;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** M8a: bảng project_members. */
public interface ProjectMemberRepository extends JpaRepository<ProjectMember, UUID> {

    boolean existsByProjectIdAndUserId(UUID projectId, UUID userId);

    Optional<ProjectMember> findByProjectIdAndUserId(UUID projectId, UUID userId);

    List<ProjectMember> findAllByProjectIdOrderByCreatedAtAsc(UUID projectId);

    long countByProjectId(UUID projectId);

    interface MemberCount {
        UUID getProjectId();

        Long getMemberCount();
    }

    @Query("select m.projectId as projectId, count(m) as memberCount from ProjectMember m "
            + "where m.projectId in :projectIds group by m.projectId")
    List<MemberCount> countByProjectIds(@Param("projectIds") Collection<UUID> projectIds);

    @Query("select m.projectId from ProjectMember m where m.userId = :userId and m.projectId in :projectIds")
    List<UUID> findJoinedAmong(@Param("userId") UUID userId, @Param("projectIds") Collection<UUID> projectIds);

    /** Rời mọi dự án của một workspace (bị xoá khỏi workspace nhóm). */
    @Modifying
    @Query(value = "delete from project_members pm using projects p where p.id = pm.project_id "
            + "and p.workspace_id = :workspaceId and pm.user_id = :userId", nativeQuery = true)
    int deleteAllInWorkspace(@Param("workspaceId") UUID workspaceId, @Param("userId") UUID userId);

    @Query(value = "select count(*) from project_members pm join projects p on p.id = pm.project_id "
            + "where p.workspace_id = :workspaceId and pm.user_id = :userId", nativeQuery = true)
    long countInWorkspace(@Param("workspaceId") UUID workspaceId, @Param("userId") UUID userId);
}
