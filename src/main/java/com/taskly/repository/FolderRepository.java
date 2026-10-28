package com.taskly.repository;

import com.taskly.entity.Folder;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** M8a: bảng folders. */
public interface FolderRepository extends JpaRepository<Folder, UUID> {

    @Query("select f from Folder f where f.workspaceId = :workspaceId order by f.sortOrder, f.id")
    List<Folder> findAllInWorkspace(@Param("workspaceId") UUID workspaceId);

    @Query("select coalesce(max(f.sortOrder), 0) from Folder f where f.workspaceId = :workspaceId")
    long maxSortOrder(@Param("workspaceId") UUID workspaceId);
}
