package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.task.TaskResponse;
import com.taskly.security.CurrentUser;
import com.taskly.service.TaskDuplicateService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class TaskDuplicateController {

    private final TaskDuplicateService taskDuplicateService;

    public TaskDuplicateController(TaskDuplicateService taskDuplicateService) {
        this.taskDuplicateService = taskDuplicateService;
    }

    /** Trả bản sao gốc. */
    @PostMapping("/api/v1/tasks/{taskId}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    @Parameter(in = ParameterIn.HEADER, name = "Idempotency-Key", description = "Chống gửi trùng (UUID v4)",
            schema = @Schema(type = "string", maxLength = 64))
    public TaskResponse duplicate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID taskId) {
        return taskDuplicateService.duplicate(CurrentUser.id(jwt), taskId);
    }
}
