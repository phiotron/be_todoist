package com.taskly.repository;

import com.taskly.entity.CalendarTaskLink;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CalendarTaskLinkRepository extends JpaRepository<CalendarTaskLink, UUID> {

    List<CalendarTaskLink> findByConnectionId(UUID connectionId);
}
