package com.taskly.repository;

import com.taskly.entity.PasswordResetToken;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    /**
     * Khoá dòng (SELECT ... FOR UPDATE) để hai request confirm cùng một token không cùng thành công.
     * CHỈ gọi bên trong transaction ghi (PasswordResetService).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /** Token của user còn dùng được tại now: chưa dùng và expires_at > now. */
    List<PasswordResetToken> findAllByUserIdAndUsedAtIsNullAndExpiresAtAfter(UUID userId, Instant now);
}
