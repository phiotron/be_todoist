package com.taskly.repository;

import com.taskly.entity.CalendarFeed;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CalendarFeedRepository extends JpaRepository<CalendarFeed, UUID> {

    Optional<CalendarFeed> findByTokenHash(String tokenHash);

    /** Feed của cả tài khoản. */
    Optional<CalendarFeed> findByUserIdAndProjectIdIsNull(UUID userId);

    Optional<CalendarFeed> findByUserIdAndProjectId(UUID userId, UUID projectId);
}
