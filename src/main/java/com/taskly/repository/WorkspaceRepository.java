package com.taskly.repository;

import com.taskly.entity.Workspace;
import com.taskly.entity.WorkspaceType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

    /**
     * Xoá mọi workspace user sở hữu bằng một câu DELETE (DB cascade: workspace_members, projects → từ M1 sections,
     * tasks…). Dùng khi xoá tài khoản; không nạp entity nào.
     */
    @Modifying
    @Query("delete from Workspace w where w.owner.id = :ownerId")
    int deleteAllByOwnerId(@Param("ownerId") UUID ownerId);

    Optional<Workspace> findByOwnerIdAndType(UUID ownerId, WorkspaceType type);

    /** M8a: workspace có link mời này. */
    Optional<Workspace> findByInviteLinkToken(String inviteLinkToken);

    /** M8a: id workspace nhóm user đang sở hữu (owner_id) — chuyển chủ khi xoá tài khoản (không nạp entity). */
    @Query("select w.id from Workspace w where w.owner.id = :ownerId and w.type = 'TEAM'")
    java.util.List<UUID> findTeamIdsByOwnerId(@Param("ownerId") UUID ownerId);

    /** M8a: chuyển owner_id bằng UPDATE trực tiếp (xoá tài khoản: không để entity cũ nằm trong persistence context). */
    @Modifying
    @Query(value = "update workspaces set owner_id = :newOwnerId where id = :workspaceId", nativeQuery = true)
    int transferOwner(@Param("workspaceId") UUID workspaceId, @Param("newOwnerId") UUID newOwnerId);
}
