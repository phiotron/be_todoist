package com.taskly.repository;

import com.taskly.entity.Filter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FilterRepository extends JpaRepository<Filter, UUID> {

    List<Filter> findByUserIdOrderBySortOrderAscIdAsc(UUID userId);

    Optional<Filter> findByIdAndUserId(UUID id, UUID userId);

    /** Móc "đọc được" cho FilterService.isReadable (M3 PinnedTargetChecker): không tải entity. */
    boolean existsByIdAndUserId(UUID id, UUID userId);

    long countByUserId(UUID userId);
}
