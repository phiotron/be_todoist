package com.taskly.service.sync.handler;

import com.taskly.dto.reminder.CreateReminderRequest;
import com.taskly.service.FilterService;
import com.taskly.service.LabelService;
import com.taskly.service.ProjectService;
import com.taskly.service.ReminderService;
import com.taskly.service.sync.SyncCommandHandler;
import com.taskly.service.sync.SyncCommandOutcome;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Lệnh đồng bộ của yêu thích (PUT / DELETE .../favorite của dự án, label, filter) và nhắc riêng của task (M9 giai đoạn 2).
 * Gọi đúng service như REST (setFavorite kiểm quyền đọc / chủ sở hữu; ReminderService kiểm quyền ghi, giới hạn REMINDERS,
 * thời điểm nhắc). Đặt / bỏ yêu thích lặp lại vô hại (như REST) nên lệnh gửi lại không cần xử lý riêng.
 * reminder_add: args = body REST + taskId (+ id tạm của app, không dùng làm khoá — ReminderService tự sinh id); lệnh trả
 * id THẬT để tempIdMapping / lệnh sau cùng request (reminder_delete với id tạm) dùng được.
 */
@Configuration(proxyBeanMethods = false)
public class FavoriteReminderSyncCommands {

    @Bean
    SyncCommandHandler projectFavoriteCommand(ProjectService projects) {
        return SyncCommandHandler.of("project_favorite", ctx -> {
            projects.setFavorite(ctx.userId(), ctx.requireId(), true);
            return SyncCommandOutcome.ok();
        });
    }

    @Bean
    SyncCommandHandler projectUnfavoriteCommand(ProjectService projects) {
        return SyncCommandHandler.of("project_unfavorite", ctx -> {
            projects.setFavorite(ctx.userId(), ctx.requireId(), false);
            return SyncCommandOutcome.ok();
        });
    }

    @Bean
    SyncCommandHandler labelFavoriteCommand(LabelService labels) {
        return SyncCommandHandler.of("label_favorite", ctx -> {
            labels.setFavorite(ctx.userId(), ctx.requireId(), true);
            return SyncCommandOutcome.ok();
        });
    }

    @Bean
    SyncCommandHandler labelUnfavoriteCommand(LabelService labels) {
        return SyncCommandHandler.of("label_unfavorite", ctx -> {
            labels.setFavorite(ctx.userId(), ctx.requireId(), false);
            return SyncCommandOutcome.ok();
        });
    }

    @Bean
    SyncCommandHandler filterFavoriteCommand(FilterService filters) {
        return SyncCommandHandler.of("filter_favorite", ctx -> {
            filters.setFavorite(ctx.userId(), ctx.requireId(), true);
            return SyncCommandOutcome.ok();
        });
    }

    @Bean
    SyncCommandHandler filterUnfavoriteCommand(FilterService filters) {
        return SyncCommandHandler.of("filter_unfavorite", ctx -> {
            filters.setFavorite(ctx.userId(), ctx.requireId(), false);
            return SyncCommandOutcome.ok();
        });
    }

    @Bean
    SyncCommandHandler reminderAddCommand(ReminderService reminders) {
        return SyncCommandHandler.creating("reminder_add", ctx -> SyncCommandOutcome.created(
                reminders.create(ctx.userId(), ctx.requireUuid("taskId"),
                        ctx.body(CreateReminderRequest.class, "id", "taskId")).id()));
    }

    @Bean
    SyncCommandHandler reminderDeleteCommand(ReminderService reminders) {
        return SyncCommandHandler.of("reminder_delete", ctx -> {
            reminders.delete(ctx.userId(), ctx.requireUuid("taskId"), ctx.requireId());
            return SyncCommandOutcome.ok();
        });
    }
}
