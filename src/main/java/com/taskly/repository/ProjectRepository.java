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
