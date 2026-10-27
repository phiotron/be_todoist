package com.taskly.repository;

import com.taskly.entity.Project;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Truy vấn dự án thêm ở Task 12–13. Không còn xoá mềm → findById là đủ. */
public interface ProjectRepository extends JpaRepository<Project, UUID> {
}
