package com.taskly.repository;

import com.taskly.entity.Location;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocationRepository extends JpaRepository<Location, UUID> {

    /** Địa điểm của user theo thứ tự tạo (GET /api/v1/locations). */
    List<Location> findByUserIdOrderByCreatedAtAscIdAsc(UUID userId);

    Optional<Location> findByIdAndUserId(UUID id, UUID userId);

    long countByUserId(UUID userId);
}
