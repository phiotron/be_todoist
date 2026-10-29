package com.taskly.repository;

import com.taskly.entity.Project;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    /** Dự án chưa lưu trữ (gồm Inbox) — ProjectService sắp lại theo cây. */
    @Query("select p from Project p where p.workspace.id in :workspaceIds and p.archivedAt is null "
            + "order by p.sortOrder, p.id")
    List<Project> findActiveIn(Collection<UUID> workspaceIds);

    @Query("select p from Project p where p.workspace.id in :workspaceIds and p.archivedAt is not null "
            + "order by p.sortOrder, p.id")
    List<Project> findArchivedIn(Collection<UUID> workspaceIds);

    /** M8a: dự án chưa lưu trữ trong tập id (phạm vi đã tham gia — AccessPolicy.listScope). */
    @Query("select p from Project p join fetch p.workspace where p.id in :projectIds and p.archivedAt is null "
            + "order by p.sortOrder, p.id")
    List<Project> findActiveAmong(Collection<UUID> projectIds);

    @Query("select p from Project p join fetch p.workspace where p.id in :projectIds and p.archivedAt is not null "
            + "order by p.sortOrder, p.id")
    List<Project> findArchivedAmong(Collection<UUID> projectIds);

    /**
     * M8a: chuyển dự án vào team → task của dự án đổi cột phi chuẩn workspace_id (native UPDATE, KHÔNG tăng version —
     * như TaskRepository.relocate). Ngoại lệ ghi chéo bảng có chủ đích: ProjectService là nơi duy nhất đổi workspace dự án.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "update tasks set workspace_id = :workspaceId, updated_at = :now where project_id = :projectId",
            nativeQuery = true)
    int moveTasksToWorkspace(UUID projectId, UUID workspaceId, java.time.Instant now);

    /** M8a: dự án có link mời này. */
    Optional<Project> findByInviteLinkToken(String inviteLinkToken);

    /** M8a Team Discovery: dự án chưa lưu trữ của workspace (lọc quyền ở service). */
    @Query("select p from Project p where p.workspace.id = :workspaceId and p.archivedAt is null "
            + "order by p.sortOrder, p.id")
    List<Project> findActiveInWorkspace(UUID workspaceId);

    /** Vùng chứa sắp xếp gốc của workspace: dự án thường (không Inbox) không có cha, gồm cả lưu trữ. */
    @Query("select p from Project p where p.workspace.id = :workspaceId and p.inbox = false and p.parentId is null "
            + "order by p.sortOrder, p.id")
    List<Project> findRootContainer(UUID workspaceId);

    /** Vùng chứa sắp xếp: con trực tiếp của parentId. */
    @Query("select p from Project p where p.workspace.id = :workspaceId and p.parentId = :parentId "
            + "order by p.sortOrder, p.id")
    List<Project> findChildContainer(UUID workspaceId, UUID parentId);

    default List<Project> findContainer(UUID workspaceId, UUID parentId) {
        return parentId == null ? findRootContainer(workspaceId) : findChildContainer(workspaceId, parentId);
    }

    /** Node cây dự án con của workspace (không Inbox, gồm cả lưu trữ): [id, parentId, sortOrder]. */
    @Query("select p.id, p.parentId, p.sortOrder from Project p where p.workspace.id = :workspaceId and p.inbox = false")
    List<Object[]> findTreeRows(UUID workspaceId);

    /** Như findTreeRows nhưng cho nhiều workspace một lần: (workspaceId, id, parentId, sortOrder). */
    @Query("select p.workspace.id, p.id, p.parentId, p.sortOrder from Project p where p.workspace.id in :workspaceIds "
            + "and p.inbox = false")
    List<Object[]> findTreeRowsIn(Collection<UUID> workspaceIds);

    @Query("select count(p) from Project p where p.workspace.id = :workspaceId and p.inbox = false "
            + "and p.archivedAt is null")
    long countActive(UUID workspaceId);

    @Query("select count(p) from Project p where p.workspace.id = :workspaceId and p.inbox = false")
    long countAll(UUID workspaceId);

    @Query("select p from Project p where p.inbox = true and p.workspace.owner.id = :userId")
    Optional<Project> findInboxOfUser(UUID userId);

    interface ProjectTaskCount {
        UUID getProjectId();

        Long getTaskCount();
    }

    /** Đếm task ĐANG MỞ theo dự án — truy vấn đếm chỉ đọc sang bảng tasks (ngoại lệ spec 8.2.2: activeTaskCount). */
    @Query(value = "select t.project_id as projectId, count(*) as taskCount from tasks t "
            + "where t.project_id in (:projectIds) and t.completed_at is null group by t.project_id", nativeQuery = true)
    List<ProjectTaskCount> countOpenTasks(Collection<UUID> projectIds);
}
