package com.taskly.repository;

import com.taskly.entity.Template;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TemplateRepository extends JpaRepository<Template, UUID> {

    /** My templates: mới nhất trước. */
    List<Template> findByUserIdOrderByCreatedAtDescIdAsc(UUID userId);

    Optional<Template> findByIdAndUserId(UUID id, UUID userId);

    long countByUserId(UUID userId);
}
