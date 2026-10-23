package com.taskly.repository;

import com.taskly.entity.RefreshToken;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /**
     * Khoá dòng (SELECT ... FOR UPDATE) để hai request refresh cùng một token không cùng thành công.
     * CHỈ gọi bên trong transaction ghi (AuthService) — transaction readOnly sẽ bị Postgres từ chối.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findByTokenHash(String tokenHash);
}
