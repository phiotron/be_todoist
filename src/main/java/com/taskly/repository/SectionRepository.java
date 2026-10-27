package com.taskly.repository;

import com.taskly.entity.Section;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SectionRepository extends JpaRepository<Section, UUID> {

    Optional<Section> findByIdAndProjectId(UUID id, UUID projectId);

    /** Section chưa lưu trữ theo thứ tự hiển thị — cũng là vùng chứa sắp xếp. */
    List<Section> findByProjectIdAndArchivedAtIsNullOrderBySortOrderAscIdAsc(UUID projectId);

    /** Section lưu trữ: lưu trữ mới nhất trước (spec 12.2). */
    List<Section> findByProjectIdAndArchivedAtIsNotNullOrderByArchivedAtDescIdAsc(UUID projectId);

    long countByProjectIdAndArchivedAtIsNull(UUID projectId);

    /** Tổng section của dự án, KỂ CẢ lưu trữ (PROJECT_SECTIONS_TOTAL ≤ 100 — spec 2.1, I1). */
    long countByProjectId(UUID projectId);

    interface SectionTaskCount {
        UUID getSectionId();

        Long getTaskCount();
    }

    /** Đếm task ĐANG MỞ theo section — truy vấn đếm chỉ đọc sang bảng tasks (ngoại lệ spec 8.2.2: activeTaskCount). */
    @Query(value = "select t.section_id as sectionId, count(*) as taskCount from tasks t "
            + "where t.section_id in (:sectionIds) and t.completed_at is null group by t.section_id", nativeQuery = true)
    List<SectionTaskCount> countOpenTasks(Collection<UUID> sectionIds);
}
