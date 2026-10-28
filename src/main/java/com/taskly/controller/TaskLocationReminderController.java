package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.location.LocationReminderResponse;
import com.taskly.security.CurrentUser;
import com.taskly.service.LocationReminderService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/v1/tasks/location-reminders (M12) — đường dẫn chữ được Spring ưu tiên hơn /api/v1/tasks/{id} (như
 * TaskReminderController của M4).
 */
@RestController
@RequestMapping("/api/v1/tasks/location-reminders")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class TaskLocationReminderController {

    private final LocationReminderService locationReminderService;

    public TaskLocationReminderController(LocationReminderService locationReminderService) {
        this.locationReminderService = locationReminderService;
    }

    @GetMapping
    public List<LocationReminderResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return locationReminderService.list(CurrentUser.id(jwt));
    }
}
