package com.taskly.repository;

import com.taskly.entity.UserTotp;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserTotpRepository extends JpaRepository<UserTotp, UUID> {

    /**
     * Khoá dòng (SELECT ... FOR UPDATE): hai request dùng cùng một mã TOTP thì chỉ một cái qua (last_used_step).
     * CHỈ gọi bên trong transaction ghi (TwoFactorService).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from UserTotp t where t.userId = :userId")
    Optional<UserTotp> findForUpdate(@Param("userId") UUID userId);
}
