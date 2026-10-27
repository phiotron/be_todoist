package com.taskly.controller;

import com.taskly.common.web.IdempotencyFilter;
import com.taskly.config.OpenApiConfig;
import com.taskly.dto.reminder.CreateReminderRequest;
import com.taskly.dto.reminder.ReminderResponse;
import com.taskly.security.CurrentUser;
import com.taskly.service.ReminderService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Nhắc riêng của một task (spec 12.8). Sửa nhắc = xoá + tạo (không có PATCH). */
@RestController
@RequestMapping("/api/v1/tasks/{taskId}/reminders")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class ReminderController {

    private final ReminderService reminderService;

    public ReminderController(ReminderService reminderService) {
        this.reminderService = reminderService;
    }

    @GetMapping
    public List<ReminderResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID taskId) {
        return reminderService.list(CurrentUser.id(jwt), taskId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Parameter(in = ParameterIn.HEADER, name = IdempotencyFilter.HEADER, required = false,
            description = "UUID v4 chống gửi trùng (server nhớ 24 giờ)", schema = @Schema(type = "string", maxLength = 64))
    public ReminderResponse create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID taskId,
                                   @Valid @RequestBody CreateReminderRequest request) {
        return reminderService.create(CurrentUser.id(jwt), taskId, request);
    }

    @DeleteMapping("/{reminderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID taskId, @PathVariable UUID reminderId) {
        reminderService.delete(CurrentUser.id(jwt), taskId, reminderId);
    }
}
