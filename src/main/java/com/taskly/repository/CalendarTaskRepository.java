package com.taskly.repository;

import com.taskly.entity.Task;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Task có lịch cho Calendar (M13b): chưa xong, có due trong [from, to], dự án / section không lưu trữ, thuộc các dự án
 * user đọc được (M8a: ReadScope) (trước M8b = task của mình; M8b đổi thành task được giao cho mình). Sắp theo due_sort, giới hạn số dòng.
 */
public interface CalendarTaskRepository extends Repository<Task, UUID> {

    @Query(value = """
            select t.* from tasks t
            join projects p on p.id = t.project_id
            left join sections s on s.id = t.section_id
            where t.workspace_id in (:workspaceIds)
              and t.project_id in (:projectIds)
              and t.completed_at is null
              and t.due_day between :from and :to
              and p.archived_at is null
              and (s.id is null or s.archived_at is null)
            order by t.due_sort, t.id
            limit :max
            """, nativeQuery = true)
    List<Task> findScheduled(@Param("workspaceIds") Collection<UUID> workspaceIds,
                             @Param("projectIds") Collection<UUID> projectIds, @Param("from") LocalDate from,
                             @Param("to") LocalDate to, @Param("max") int max);

    /** M8a: phạm vi dự án đã tham gia (ReadScope) — người cộng tác không nhận task của dự án không chia sẻ. */
    default List<Task> findScheduled(com.taskly.security.ReadScope scope, LocalDate from, LocalDate to, int max) {
        return scope.isEmpty() ? List.of()
                : findScheduled(scope.workspaceIds(), scope.projectIds(), from, to, max);
    }

    @Query(value = """
            select t.* from tasks t
            left join sections s on s.id = t.section_id
            where t.project_id = :projectId
              and t.completed_at is null
              and t.due_day between :from and :to
              and (s.id is null or s.archived_at is null)
            order by t.due_sort, t.id
            limit :max
            """, nativeQuery = true)
    List<Task> findScheduledInProject(@Param("projectId") UUID projectId, @Param("from") LocalDate from,
                                      @Param("to") LocalDate to, @Param("max") int max);
}
