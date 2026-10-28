package com.taskly.service.sync.handler;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.dto.section.CreateSectionRequest;
import com.taskly.dto.section.UpdateSectionRequest;
import com.taskly.entity.Section;
import com.taskly.repository.SectionRepository;
import com.taskly.service.SectionService;
import com.taskly.service.sync.SyncCommandContext;
import com.taskly.service.sync.SyncCommandHandler;
import com.taskly.service.sync.SyncCommandOutcome;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Lệnh đồng bộ của section (M1). section_add cần projectId; lệnh khác nhận projectId tuỳ chọn (vắng → tra theo id; quyền
 * vẫn do SectionService kiểm theo dự án — section của người khác ra 404 như REST).
 */
@Configuration(proxyBeanMethods = false)
public class SectionSyncCommands {

    @Bean
    SyncCommandHandler sectionAddCommand(SectionService sections) {
        return SyncCommandHandler.creating("section_add", ctx -> SyncCommandOutcome.created(sections.create(
                ctx.userId(), ctx.requireUuid("projectId"), ctx.body(CreateSectionRequest.class, "projectId")).body().id()));
    }

    @Bean
    SyncCommandHandler sectionUpdateCommand(SectionService sections, SectionRepository repository) {
        return SyncCommandHandler.of("section_update", ctx -> {
            UUID id = ctx.requireId();
            sections.update(ctx.userId(), projectOf(ctx, id, repository), id,
                    ctx.body(UpdateSectionRequest.class, "id", "projectId"));
            return SyncCommandOutcome.ok();
        });
    }

    @Bean
    SyncCommandHandler sectionArchiveCommand(SectionService sections, SectionRepository repository) {
        return SyncCommandHandler.of("section_archive", ctx -> {
            UUID id = ctx.requireId();
            sections.archive(ctx.userId(), projectOf(ctx, id, repository), id);
            return SyncCommandOutcome.ok();
        });
    }

    @Bean
    SyncCommandHandler sectionUnarchiveCommand(SectionService sections, SectionRepository repository) {
        return SyncCommandHandler.of("section_unarchive", ctx -> {
            UUID id = ctx.requireId();
            sections.unarchive(ctx.userId(), projectOf(ctx, id, repository), id);
            return SyncCommandOutcome.ok();
        });
    }

    @Bean
    SyncCommandHandler sectionDeleteCommand(SectionService sections, SectionRepository repository) {
        return SyncCommandHandler.of("section_delete", ctx -> {
            UUID id = ctx.requireId();
            sections.delete(ctx.userId(), projectOf(ctx, id, repository), id);
            return SyncCommandOutcome.ok();
        });
    }

    private static UUID projectOf(SyncCommandContext ctx, UUID sectionId, SectionRepository repository) {
        UUID projectId = ctx.optionalUuid("projectId");
        if (projectId != null) {
            return projectId;
        }
        return repository.findById(sectionId).map(Section::getProjectId)
                .orElseThrow(() -> ApiException.notFound(ErrorMessage.SECTION_NOT_FOUND));
    }
}
