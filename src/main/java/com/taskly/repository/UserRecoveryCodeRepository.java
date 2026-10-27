package com.taskly.repository;

import com.taskly.entity.UserRecoveryCode;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface UserRecoveryCodeRepository extends JpaRepository<UserRecoveryCode, UUID> {

    /** Mã chưa dùng khớp hash, khoá dòng → một mã chỉ thành công một lần. CHỈ gọi trong transaction ghi. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<UserRecoveryCode> findByUserIdAndCodeHashAndUsedAtIsNull(UUID userId, String codeHash);

    long countByUserIdAndUsedAtIsNull(UUID userId);

    List<UserRecoveryCode> findAllByUserId(UUID userId);
}
