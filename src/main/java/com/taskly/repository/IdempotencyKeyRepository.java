package com.taskly.repository;

import com.taskly.entity.IdempotencyKey;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Mọi lệnh ghi chạy trong transaction riêng do IdempotencyService mở (REQUIRES_NEW). */
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {

    /** Không khoá dòng: chống đua do ràng buộc unique (user_id, idem_key, method, path) lo. */
    Optional<IdempotencyKey> findByUserIdAndIdemKeyAndMethodAndPath(UUID userId, String idemKey, String method,
                                                                    String path);

    /** Chiếm lại hàng đã hết hạn (đang xử lý quá 60 giây, hoặc kết quả quá 24 giờ). Đua → chỉ một bên xoá được. */
    @Modifying
    @Query("delete from IdempotencyKey k where k.id = :id and k.expiresAt < :now")
    int deleteIfExpired(UUID id, Instant now);

    /** Request lỗi → bỏ hàng khoá để client gửi lại được. */
    @Modifying
    @Query("delete from IdempotencyKey k where k.id = :id")
    int deleteRow(UUID id);

    /** Ghi kết quả 2xx và đổi hạn từ 60 giây sang 24 giờ. */
    @Modifying
    @Query("update IdempotencyKey k set k.status = :status, k.responseBody = :body, k.expiresAt = :expiresAt "
            + "where k.id = :id")
    int markDone(UUID id, int status, String body, Instant expiresAt);

    @Modifying
    @Query("delete from IdempotencyKey k where k.expiresAt < :now")
    int deleteExpired(Instant now);
}
