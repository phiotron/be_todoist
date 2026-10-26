package com.taskly.repository;

import com.taskly.entity.IdentityProvider;
import com.taskly.entity.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /** User đã liên kết (provider, subject) — subject là claim "sub" của Google. */
    @Query("select i.user from UserIdentity i where i.provider = :provider and i.subject = :subject")
    Optional<User> findByIdentity(@Param("provider") IdentityProvider provider, @Param("subject") String subject);
}
