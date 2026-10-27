package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.reminder.ReminderOccurrence;
import com.taskly.security.CurrentUser;
import com.taskly.service.ReminderOccurrenceService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/v1/tasks/reminders — đường dẫn chữ được Spring ưu tiên hơn /api/v1/tasks/{id} (TaskController, M1).
 * limit không phải số → 400 do GlobalExceptionHandler (type mismatch); ngoài 1..50 → 400 từ ReminderWindow.limit.
 */
@RestController
@RequestMapping("/api/v1/tasks/reminders")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class TaskReminderController {

    private final ReminderOccurrenceService reminderOccurrenceService;

    public TaskReminderController(ReminderOccurrenceService reminderOccurrenceService) {
        this.reminderOccurrenceService = reminderOccurrenceService;
    }

    @GetMapping
    public List<ReminderOccurrence> upcoming(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Số lần nhắc tối đa (1–50), mặc định 50",
                    schema = @Schema(type = "integer", minimum = "1", maximum = "50", defaultValue = "50"))
            @RequestParam(required = false) Integer limit) {
        return reminderOccurrenceService.upcoming(CurrentUser.id(jwt), limit);
    }
}
