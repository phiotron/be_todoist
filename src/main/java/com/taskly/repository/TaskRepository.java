package com.taskly.repository;

import com.taskly.entity.Task;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * Truy vấn đọc thêm ở Part 2 Task 15. Hai native UPDATE dưới đây là CÁCH DUY NHẤT đổi sort_order / chuyển cây con (spec
 * 5.6, C4): KHÔNG tăng version, vẫn ghi updated_at, flush rồi xoá persistence context (entity cũ thành detached — chỉ
 * đọc; cần sửa tiếp thì nạp lại). Chỉ TaskService gọi qua writeSortOrder(s) / relocate.
 */
public interface TaskRepository extends JpaRepository<Task, UUID> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update tasks set sort_order = :sortOrder, updated_at = :now where id = :id", nativeQuery = true)
    int updateSortOrder(UUID id, long sortOrder, Instant now);

    /** Cây con đi theo task được chuyển: đổi project_id / section_id / workspace_id. section null → cast tường minh. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update tasks set project_id = :projectId, section_id = cast(:sectionId as uuid), "
            + "workspace_id = :workspaceId, updated_at = :now where id in (:ids)", nativeQuery = true)
    int relocate(Collection<UUID> ids, UUID projectId, UUID sectionId, UUID workspaceId, Instant now);
}
