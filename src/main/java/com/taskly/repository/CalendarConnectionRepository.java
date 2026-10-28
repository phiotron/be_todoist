package com.taskly.repository;

import com.taskly.entity.CalendarConnection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface CalendarConnectionRepository extends JpaRepository<CalendarConnection, UUID> {

    Optional<CalendarConnection> findByUserId(UUID userId);

    /** Job sync: kết nối đang bật sync, hoặc đã tắt nhưng còn sự kiện đã đẩy cần dọn. */
    @Query("""
            select c.userId from CalendarConnection c
            where c.needsReauth = false and (c.syncTasks = true
                or exists (select 1 from CalendarTaskLink l where l.connectionId = c.id))
            """)
    List<UUID> findUserIdsToSync();

    /** Ghi một cột (không merge cả entity → không đè công tắc người dùng vừa đổi trong lúc sync). */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update CalendarConnection c set c.lastSyncedAt = :at where c.id = :id")
    int markSynced(@Param("id") UUID id, @Param("at") Instant at);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update CalendarConnection c set c.tasklyCalendarId = :calendarId where c.id = :id")
    int updateTasklyCalendarId(@Param("id") UUID id, @Param("calendarId") String calendarId);
}
