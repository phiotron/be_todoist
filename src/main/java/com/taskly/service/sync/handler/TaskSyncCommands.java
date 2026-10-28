package com.taskly.service.sync.handler;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.common.error.ProblemCodes;
import com.taskly.dto.task.CreateTaskRequest;
import com.taskly.dto.task.MoveTaskRequest;
import com.taskly.dto.task.UpdateTaskRequest;
import com.taskly.service.TaskCompletionService;
import com.taskly.service.TaskMoveService;
import com.taskly.service.TaskService;
import com.taskly.service.sync.SyncCommandContext;
import com.taskly.service.sync.SyncCommandHandler;
import com.taskly.service.sync.SyncCommandOutcome;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.node.ObjectNode;

/**
 * Lệnh đồng bộ của task (M1 + label M2). task_update theo quy tắc xung đột (plan M9 F4 — mặc định, chờ duyệt):
 * field-lww = ghi sau thắng theo TỪNG TRƯỜNG: args chỉ mang field đã đổi, version lấy của server ngay trong transaction
 * của lệnh (không 409 giả); version = dùng version app gửi, lệch → 409 sync-conflict kèm "current".
 */
@Configuration(proxyBeanMethods = false)
public class TaskSyncCommands {

    public static final String POLICY_FIELD_LWW = "field-lww";
    public static final String POLICY_VERSION = "version";

    @Bean
    SyncCommandHandler taskAddCommand(TaskService tasks) {
        return SyncCommandHandler.creating("task_add", ctx -> SyncCommandOutcome.created(
                tasks.create(ctx.userId(), ctx.body(CreateTaskRequest.class)).body().id()));
    }

    @Bean
    public SyncCommandHandler taskUpdateCommand(TaskService tasks,
                                         @Value("${taskly.sync.conflict-policy:" + POLICY_FIELD_LWW + "}") String policy) {
        boolean versioned = POLICY_VERSION.equals(policy);
        return SyncCommandHandler.of("task_update", ctx -> {
            UUID id = ctx.requireId();
            ObjectNode node = ctx.argsCopy();
            node.remove("id");
            if (!versioned) {
                node.put("version", tasks.requireReadable(ctx.userId(), id).getVersion());
            }
            UpdateTaskRequest request = ctx.read(node, UpdateTaskRequest.class);
            try {
                tasks.update(ctx.userId(), id, request);
            } catch (ApiException e) {
                if (versioned && isVersionConflict(e)) {
                    throw syncConflict(e);
                }
                throw e;
            }
            return SyncCommandOutcome.ok();
        });
    }

    @Bean
    SyncCommandHandler taskMoveCommand(TaskMoveService moves) {
        return SyncCommandHandler.of("task_move", ctx -> {
            moves.move(ctx.userId(), ctx.requireId(), ctx.body(MoveTaskRequest.class, "id"));
            return SyncCommandOutcome.ok();
        });
    }

    @Bean
    SyncCommandHandler taskCloseCommand(TaskCompletionService completion) {
        return SyncCommandHandler.of("task_close", ctx -> {
            completion.close(ctx.userId(), ctx.requireId());
            return SyncCommandOutcome.ok();
        });
    }

    @Bean
    SyncCommandHandler taskReopenCommand(TaskCompletionService completion) {
        return SyncCommandHandler.of("task_reopen", ctx -> {
            completion.reopen(ctx.userId(), ctx.requireId(), undo(ctx));
            return SyncCommandOutcome.ok();
        });
    }

    @Bean
    SyncCommandHandler taskDeleteCommand(TaskService tasks) {
        return SyncCommandHandler.of("task_delete", ctx -> {
            tasks.delete(ctx.userId(), ctx.requireId());
            return SyncCommandOutcome.ok();
        });
    }

    private static boolean undo(SyncCommandContext ctx) {
        return ctx.has("undo") && ctx.argsCopy().get("undo").asBoolean(false);
    }

    private static boolean isVersionConflict(ApiException e) {
        var properties = e.getBody().getProperties();
        return properties != null && ProblemCodes.VERSION_CONFLICT.equals(properties.get(ProblemCodes.PROPERTY));
    }

    private static ApiException syncConflict(ApiException original) {
        ApiException conflict = ApiException.conflict(ErrorMessage.SYNC_CONFLICT).withCode(ProblemCodes.SYNC_CONFLICT);
        var properties = original.getBody().getProperties();
        if (properties != null && properties.containsKey("current")) {
            conflict.withProperty("current", properties.get("current"));
        }
        return conflict;
    }
}
