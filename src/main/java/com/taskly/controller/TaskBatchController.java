package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.task.AffectedTasksResponse;
import com.taskly.dto.task.BatchTasksRequest;
import com.taskly.dto.task.RescheduleOverdueRequest;
import com.taskly.security.CurrentUser;
import com.taskly.service.TaskBatchService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Chọn nhiều + Reschedule (spec 12.5). `tasks/batch` nhận Idempotency-Key (MOVE / DELETE không idempotent nếu thiếu
 * key — app luôn gửi key); Reschedule không cần (dời cùng một ngày lặp lại cho cùng kết quả).
 */
@RestController
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class TaskBatchController {

    private final TaskBatchService taskBatchService;

    public TaskBatchController(TaskBatchService taskBatchService) {
        this.taskBatchService = taskBatchService;
    }

    @PostMapping("/api/v1/tasks/batch")
    @Parameter(in = ParameterIn.HEADER, name = "Idempotency-Key", description = "Chống gửi trùng (UUID v4); MOVE / DELETE không idempotent nếu thiếu",
            schema = @Schema(type = "string", maxLength = 64))
    public AffectedTasksResponse batch(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BatchTasksRequest request) {
        return taskBatchService.execute(CurrentUser.id(jwt), request);
    }

    @PostMapping("/api/v1/tasks/overdue/reschedule")
    public AffectedTasksResponse rescheduleOverdue(@AuthenticationPrincipal Jwt jwt,
                                                   @Valid @RequestBody RescheduleOverdueRequest request) {
        return taskBatchService.rescheduleOverdue(CurrentUser.id(jwt), request);
    }
}
