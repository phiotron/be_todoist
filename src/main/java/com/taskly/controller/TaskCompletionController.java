package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.task.CloseTaskRequest;
import com.taskly.dto.task.ReopenTaskRequest;
import com.taskly.dto.task.TaskChangeResponse;
import com.taskly.security.CurrentUser;
import com.taskly.service.TaskCompletionService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tasks/{taskId}")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class TaskCompletionController {

    private final TaskCompletionService taskCompletionService;

    public TaskCompletionController(TaskCompletionService taskCompletionService) {
        this.taskCompletionService = taskCompletionService;
    }

    /**
     * Body tuỳ chọn {forever?, resetSubtasks?} (M6c). Task lặp (không forever) → KHÔNG đặt completedAt, due nhảy sang kỳ
     * kế; `cascaded` = task con vừa được reset. Nhận Idempotency-Key (gửi lặp không nhảy thêm kỳ).
     */
    @PostMapping("/close")
    public TaskChangeResponse close(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID taskId,
                                    @RequestBody(required = false) CloseTaskRequest request) {
        return taskCompletionService.close(CurrentUser.id(jwt), taskId, request != null && request.isForever(),
                request == null ? null : request.resetSubtasks());
    }

    /** Body tuỳ chọn {undo: boolean}; undo = true khi app bấm "Hoàn tác" trên snackbar. */
    @PostMapping("/reopen")
    public TaskChangeResponse reopen(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID taskId,
                                     @RequestBody(required = false) ReopenTaskRequest request) {
        return taskCompletionService.reopen(CurrentUser.id(jwt), taskId, request != null && request.isUndo());
    }
}
