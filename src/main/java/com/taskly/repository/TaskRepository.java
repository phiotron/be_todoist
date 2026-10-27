package com.taskly.repository;

import com.taskly.entity.Task;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
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

    // ---- truy vấn đọc (Task 15) ----

    /** Task đang mở của một section (mọi cấp — sub-task cùng section với task gốc). */
    List<Task> findBySectionIdAndCompletedAtIsNull(UUID sectionId);

    /** Vùng chứa của subtask: con trực tiếp của parentId. */
    List<Task> findByParentIdOrderBySortOrderAscIdAsc(UUID parentId);

    /** Vùng chứa task gốc "không thuộc section" của dự án. */
    List<Task> findByProjectIdAndParentIdIsNullAndSectionIdIsNullOrderBySortOrderAscIdAsc(UUID projectId);

    /** Vùng chứa task gốc của một section. */
    List<Task> findByProjectIdAndParentIdIsNullAndSectionIdOrderBySortOrderAscIdAsc(UUID projectId, UUID sectionId);

    /** Node của cây công việc con trong dự án: [id, parentId, sortOrder, completedAt, completionBatchId]. */
    @Query("select t.id, t.parentId, t.sortOrder, t.completedAt, t.completionBatchId from Task t "
            + "where t.projectId = :projectId")
    List<Object[]> findTreeRows(UUID projectId);

    /** JPQL (không native) để Hibernate tự flush thay đổi chưa ghi trước khi đếm — đếm đúng khi gọi nhiều lần / lô. */
    @Query("select count(t) from Task t where t.projectId = :projectId and t.completedAt is null")
    long countOpenInProject(UUID projectId);

    /**
     * Khối Quá hạn chưa xong trong dự án CHƯA lưu trữ của các workspace (spec 5.1, 12.5): due_day < today, tức
     * due_date < today hoặc due_datetime < 00:00 hôm nay (startOfToday) — task có giờ đã qua TRONG hôm nay KHÔNG tính.
     * Sắp theo due để kết quả ổn định.
     */
    @Query("select t from Task t where t.completedAt is null "
            + "and (t.dueDate < :today or t.dueDatetime < :startOfToday) "
            + "and t.workspaceId in :workspaceIds "
            + "and t.projectId in (select p.id from Project p where p.archivedAt is null) "
            + "order by t.dueDate, t.dueDatetime, t.id")
    List<Task> findOverdue(LocalDate today, LocalDateTime startOfToday, Collection<UUID> workspaceIds);

    default List<Task> findContainer(UUID projectId, UUID sectionId, UUID parentId) {
        if (parentId != null) {
            return findByParentIdOrderBySortOrderAscIdAsc(parentId);
        }
        return sectionId == null
                ? findByProjectIdAndParentIdIsNullAndSectionIdIsNullOrderBySortOrderAscIdAsc(projectId)
                : findByProjectIdAndParentIdIsNullAndSectionIdOrderBySortOrderAscIdAsc(projectId, sectionId);
    }
}
