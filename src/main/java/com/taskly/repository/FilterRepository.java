package com.taskly.repository;

import com.taskly.entity.Filter;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FilterRepository extends JpaRepository<Filter, UUID> {

    List<Filter> findByUserIdOrderBySortOrderAscIdAsc(UUID userId);

    Optional<Filter> findByIdAndUserId(UUID id, UUID userId);

    /** Móc "đọc được" cho FilterService.isReadable (M3 PinnedTargetChecker): không tải entity. */
    boolean existsByIdAndUserId(UUID id, UUID userId);

    long countByUserId(UUID userId);

    /** Native UPDATE: chỉ đổi sort_order (+ updated_at), không qua entity, không đụng tasks (spec 8.2 quy tắc 11). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE filters SET sort_order = :sortOrder, updated_at = :now WHERE id = :id", nativeQuery = true)
    int updateSortOrder(@Param("id") UUID id, @Param("sortOrder") long sortOrder, @Param("now") Instant now);
}
