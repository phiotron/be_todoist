package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.task.MoveTaskRequest;
import com.taskly.dto.task.TaskResponse;
import com.taskly.security.CurrentUser;
import com.taskly.service.TaskMoveService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class TaskMoveController {

    private final TaskMoveService taskMoveService;

    public TaskMoveController(TaskMoveService taskMoveService) {
        this.taskMoveService = taskMoveService;
    }

    @PostMapping("/api/v1/tasks/{taskId}/move")
    public TaskResponse move(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID taskId,
                             @Valid @RequestBody MoveTaskRequest request) {
        return taskMoveService.move(CurrentUser.id(jwt), taskId, request);
    }
}
