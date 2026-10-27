package com.taskly.repository;

import com.taskly.entity.Reminder;
import com.taskly.entity.ReminderType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ReminderRepository extends JpaRepository<Reminder, UUID> {

    /** Nhắc riêng của một user trên một task, theo thứ tự tạo (GET tasks/{id}/reminders). */
    List<Reminder> findByTaskIdAndUserIdOrderByCreatedAtAscIdAsc(UUID taskId, UUID userId);

    Optional<Reminder> findByIdAndTaskIdAndUserId(UUID id, UUID taskId, UUID userId);

    /** Số nhắc riêng của user trên task CHƯA xong — đếm cho giới hạn 700 (spec 2.1, LimitKind.REMINDERS). */
    @Query(value = """
            select count(*) from reminders r
            join tasks t on t.id = r.task_id
            where r.user_id = :userId and t.completed_at is null
            """, nativeQuery = true)
    long countOnOpenTasks(@Param("userId") UUID userId);

    /**
     * Xoá các nhắc loại `type` của những task trong danh sách (listener TaskTimeClearedEvent xoá RELATIVE khi task
     * mất giờ). @Transactional: truy vấn @Modifying khai báo trong interface không tự có transaction (gọi trong
     * transaction của ReminderService thì tham gia). KHÔNG dùng clearAutomatically: sẽ làm mất thay đổi chưa flush của
     * Task đang được ghi cùng transaction. Trả số dòng đã xoá. taskIds rỗng: caller bỏ qua (không gọi).
     */
    @Modifying
    @Transactional
    @Query("delete from Reminder r where r.taskId in :taskIds and r.type = :type")
    int deleteByTaskIdsAndType(@Param("taskIds") Collection<UUID> taskIds, @Param("type") ReminderType type);
}
