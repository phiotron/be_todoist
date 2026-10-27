package com.taskly.repository;

import com.taskly.entity.RefreshToken;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /**
     * Khoá dòng (SELECT ... FOR UPDATE) để hai request refresh cùng một token không cùng thành công.
     * CHỈ gọi bên trong transaction ghi (AuthService) — transaction readOnly sẽ bị Postgres từ chối.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Refresh token chưa thu hồi của user (kể cả đã hết hạn — thu hồi luôn cho gọn). */
    List<RefreshToken> findAllByUserIdAndRevokedAtIsNull(UUID userId);

    /** Thu hồi mọi token còn sống của một họ; token đã thu hồi giữ nguyên revoked_at cũ. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now, t.updatedAt = :now "
            + "where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(UUID familyId, Instant now);
}
