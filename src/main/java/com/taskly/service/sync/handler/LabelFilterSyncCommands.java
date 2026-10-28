package com.taskly.service.sync.handler;

import com.taskly.dto.filter.CreateFilterRequest;
import com.taskly.dto.filter.UpdateFilterRequest;
import com.taskly.dto.label.CreateLabelRequest;
import com.taskly.dto.label.UpdateLabelRequest;
import com.taskly.service.FilterService;
import com.taskly.service.LabelService;
import com.taskly.service.sync.SyncCommandHandler;
import com.taskly.service.sync.SyncCommandOutcome;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Lệnh đồng bộ của label và filter (M2): args = body REST + id; giới hạn / trùng tên / cú pháp do service kiểm. */
@Configuration(proxyBeanMethods = false)
public class LabelFilterSyncCommands {

    @Bean
    SyncCommandHandler labelAddCommand(LabelService labels) {
        return SyncCommandHandler.creating("label_add", ctx -> SyncCommandOutcome.created(
                labels.create(ctx.userId(), ctx.body(CreateLabelRequest.class)).body().id()));
    }

    @Bean
    SyncCommandHandler labelUpdateCommand(LabelService labels) {
        return SyncCommandHandler.of("label_update", ctx -> {
            labels.update(ctx.userId(), ctx.requireId(), ctx.body(UpdateLabelRequest.class, "id"));
            return SyncCommandOutcome.ok();
        });
    }

    @Bean
    SyncCommandHandler labelDeleteCommand(LabelService labels) {
        return SyncCommandHandler.of("label_delete", ctx -> {
            labels.delete(ctx.userId(), ctx.requireId());
            return SyncCommandOutcome.ok();
        });
    }

    @Bean
    SyncCommandHandler filterAddCommand(FilterService filters) {
        return SyncCommandHandler.creating("filter_add", ctx -> SyncCommandOutcome.created(
                filters.create(ctx.userId(), ctx.body(CreateFilterRequest.class)).body().id()));
    }

    @Bean
    SyncCommandHandler filterUpdateCommand(FilterService filters) {
        return SyncCommandHandler.of("filter_update", ctx -> {
            filters.update(ctx.userId(), ctx.requireId(), ctx.body(UpdateFilterRequest.class, "id"));
            return SyncCommandOutcome.ok();
        });
    }

    @Bean
    SyncCommandHandler filterDeleteCommand(FilterService filters) {
        return SyncCommandHandler.of("filter_delete", ctx -> {
            filters.delete(ctx.userId(), ctx.requireId());
            return SyncCommandOutcome.ok();
        });
    }
}
