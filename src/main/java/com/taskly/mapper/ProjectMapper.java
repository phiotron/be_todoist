package com.taskly.mapper;

import com.taskly.dto.project.ProjectResponse;
import com.taskly.entity.Project;

public final class ProjectMapper {

    private ProjectMapper() {
    }

    /** M8a: thông tin chia sẻ của dự án theo góc nhìn người gọi. */
    public record Sharing(long memberCount, boolean joined, boolean canManage) {
        /** Dự án cá nhân chưa chia sẻ, người gọi là chủ. */
        public static final Sharing PRIVATE_OWNER = new Sharing(1, true, true);
    }

    /** Bản M1 (không có thông tin chia sẻ) — coi như dự án cá nhân của chính người gọi. */
    public static ProjectResponse toResponse(Project project, boolean favorite, long activeTaskCount,
                                             long descendantProjectCount, long descendantActiveTaskCount) {
        return toResponse(project, favorite, activeTaskCount, descendantProjectCount, descendantActiveTaskCount,
                Sharing.PRIVATE_OWNER);
    }

    public static ProjectResponse toResponse(Project project, boolean favorite, long activeTaskCount,
                                             long descendantProjectCount, long descendantActiveTaskCount,
                                             Sharing sharing) {
        return new ProjectResponse(project.getId(), project.getName(), project.getColor(), project.getDescription(),
                project.getParentId(), project.isInbox(), favorite, project.getArchivedAt(), project.getSortOrder(),
                project.getViewStyle(), activeTaskCount, descendantProjectCount, descendantActiveTaskCount,
                project.getCreatedAt(), project.getUpdatedAt(), project.getWorkspace().getId(), project.getFolderId(),
                project.getAccess(), sharing.memberCount(), sharing.joined(), sharing.canManage());
    }
}
