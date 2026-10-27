package com.taskly.mapper;

import com.taskly.dto.project.ProjectResponse;
import com.taskly.entity.Project;

public final class ProjectMapper {

    private ProjectMapper() {
    }

    public static ProjectResponse toResponse(Project project, boolean favorite, long activeTaskCount,
                                             long descendantProjectCount, long descendantActiveTaskCount) {
        return new ProjectResponse(project.getId(), project.getName(), project.getColor(), project.getDescription(),
                project.getParentId(), project.isInbox(), favorite, project.getArchivedAt(), project.getSortOrder(),
                project.getViewStyle(), activeTaskCount, descendantProjectCount, descendantActiveTaskCount,
                project.getCreatedAt(), project.getUpdatedAt());
    }
}
