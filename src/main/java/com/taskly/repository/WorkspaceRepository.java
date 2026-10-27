package com.taskly.repository;

import com.taskly.entity.Workspace;
import com.taskly.entity.WorkspaceType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

    Optional<Workspace> findByOwnerIdAndType(UUID ownerId, WorkspaceType type);
}
