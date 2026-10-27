package com.taskly.repository;

import com.taskly.entity.Section;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Truy vấn section thêm ở Task 14. */
public interface SectionRepository extends JpaRepository<Section, UUID> {
}
