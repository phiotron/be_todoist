package com.taskly.repository;

import com.taskly.entity.Label;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LabelRepository extends JpaRepository<Label, UUID> {

    List<Label> findByUserIdOrderBySortOrderAscIdAsc(UUID userId);

    Optional<Label> findByIdAndUserId(UUID id, UUID userId);

    List<Label> findByUserIdAndIdIn(UUID userId, Collection<UUID> ids);

    /** Móc "đọc được" cho LabelService.isReadable (M3 PinnedTargetChecker): không tải entity. */
    boolean existsByIdAndUserId(UUID id, UUID userId);

    long countByUserId(UUID userId);

    /** So bằng lower() ở DB — cùng quy tắc với chỉ mục unique ux_labels_user_lower_name. */
    @Query("select count(l) > 0 from Label l where l.userId = :userId and lower(l.name) = lower(:name)")
    boolean existsWithName(@Param("userId") UUID userId, @Param("name") String name);

    @Query("select count(l) > 0 from Label l where l.userId = :userId and lower(l.name) = lower(:name)"
            + " and l.id <> :excludeId")
    boolean existsOtherWithName(@Param("userId") UUID userId, @Param("name") String name,
                                @Param("excludeId") UUID excludeId);

    /** Native UPDATE: chỉ đổi sort_order (+ updated_at), không qua entity, không đụng tasks (spec 8.2 quy tắc 11). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE labels SET sort_order = :sortOrder, updated_at = :now WHERE id = :id", nativeQuery = true)
    int updateSortOrder(@Param("id") UUID id, @Param("sortOrder") long sortOrder, @Param("now") Instant now);
}
