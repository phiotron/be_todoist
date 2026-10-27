package com.taskly.repository;

import com.taskly.entity.IdempotencyKey;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Truy vấn khoá chống gửi trùng thêm ở Task 16. */
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {
}
