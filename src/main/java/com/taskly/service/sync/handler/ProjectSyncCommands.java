package com.taskly.service.sync.handler;

import com.taskly.dto.project.CreateProjectRequest;
import com.taskly.dto.project.MoveProjectRequest;
import com.taskly.dto.project.UpdateProjectRequest;
import com.taskly.service.ProjectService;
import com.taskly.service.sync.SyncCommandHandler;
import com.taskly.service.sync.SyncCommandOutcome;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Lệnh đồng bộ của dự án (M1): args = body REST + id. Mọi kiểm quyền / giới hạn / khoá do ProjectService. */
@Configuration(proxyBeanMethods = false)
public class ProjectSyncCommands {

    @Bean
    SyncCommandHandler projectAddCommand(ProjectService projects) {
        return SyncCommandHandler.creating("project_add", ctx -> SyncCommandOutcome.created(
                projects.create(ctx.userId(), ctx.body(CreateProjectRequest.class)).body().id()));
    }

    @Bean
    SyncCommandHandler projectUpdateCommand(ProjectService projects) {
        return SyncCommandHandler.of("project_update", ctx -> {
            projects.update(ctx.userId(), ctx.requireId(), ctx.body(UpdateProjectRequest.class, "id"));
            return SyncCommandOutcome.ok();
        });
    }

    @Bean
    SyncCommandHandler projectMoveCommand(ProjectService projects) {
        return SyncCommandHandler.of("project_move", ctx -> {
            projects.move(ctx.userId(), ctx.requireId(), ctx.body(MoveProjectRequest.class, "id"));
            return SyncCommandOutcome.ok();
        });
    }

    @Bean
    SyncCommandHandler projectArchiveCommand(ProjectService projects) {
        return SyncCommandHandler.of("project_archive", ctx -> {
            projects.archive(ctx.userId(), ctx.requireId());
            return SyncCommandOutcome.ok();
        });
    }

    @Bean
    SyncCommandHandler projectUnarchiveCommand(ProjectService projects) {
        return SyncCommandHandler.of("project_unarchive", ctx -> {
            projects.unarchive(ctx.userId(), ctx.requireId());
            return SyncCommandOutcome.ok();
        });
    }

    @Bean
    SyncCommandHandler projectDeleteCommand(ProjectService projects) {
        return SyncCommandHandler.of("project_delete", ctx -> {
            projects.delete(ctx.userId(), ctx.requireId());
            return SyncCommandOutcome.ok();
        });
    }
}
