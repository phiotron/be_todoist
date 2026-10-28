package com.taskly.repository;

import com.taskly.entity.Invitation;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** M8a: bảng invitations (lời mời email). */
public interface InvitationRepository extends JpaRepository<Invitation, UUID> {

    /** Khoá hàng → một token chỉ được chấp nhận một lần dù hai request song song. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invitation i where i.tokenHash = :tokenHash")
    Optional<Invitation> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invitation i where i.id = :id")
    Optional<Invitation> findByIdForUpdate(@Param("id") UUID id);

    @Query("select i from Invitation i where i.projectId = :projectId and i.acceptedAt is null "
            + "and i.declinedAt is null and i.expiresAt > :now order by i.createdAt, i.id")
    List<Invitation> findPendingForProject(@Param("projectId") UUID projectId, @Param("now") Instant now);

    @Query("select i from Invitation i where i.workspaceId = :workspaceId and i.acceptedAt is null "
            + "and i.declinedAt is null and i.expiresAt > :now order by i.createdAt, i.id")
    List<Invitation> findPendingForWorkspace(@Param("workspaceId") UUID workspaceId, @Param("now") Instant now);

    @Query("select i from Invitation i where i.email = :email and i.acceptedAt is null "
            + "and i.declinedAt is null and i.expiresAt > :now order by i.createdAt, i.id")
    List<Invitation> findPendingForEmail(@Param("email") String email, @Param("now") Instant now);

    @Query("select count(i) from Invitation i where i.projectId = :projectId and i.acceptedAt is null "
            + "and i.declinedAt is null and i.expiresAt > :now")
    long countPendingForProject(@Param("projectId") UUID projectId, @Param("now") Instant now);

    /** Mời lại cùng email → lời mời cũ còn treo (chưa dùng) bị xoá trước khi tạo cái mới. */
    @Modifying
    @Query("delete from Invitation i where i.projectId = :projectId and i.email = :email and i.acceptedAt is null")
    int deleteOpenForProject(@Param("projectId") UUID projectId, @Param("email") String email);

    @Modifying
    @Query("delete from Invitation i where i.workspaceId = :workspaceId and i.email = :email "
            + "and i.acceptedAt is null")
    int deleteOpenForWorkspace(@Param("workspaceId") UUID workspaceId, @Param("email") String email);
}
