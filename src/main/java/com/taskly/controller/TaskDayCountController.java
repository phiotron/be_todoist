package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.task.DayCountsResponse;
import com.taskly.security.CurrentUser;
import com.taskly.service.TaskDayCountService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** M6b: lịch của Date sheet ("Wed 28 Oct · 3 tasks") — GET /api/v1/tasks/day-counts. */
@RestController
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class TaskDayCountController {

    private final TaskDayCountService taskDayCountService;

    public TaskDayCountController(TaskDayCountService taskDayCountService) {
        this.taskDayCountService = taskDayCountService;
    }

    /** from ≤ to, tối đa 62 ngày (vượt → 400); ngày quá khứ vẫn đếm. */
    @GetMapping("/api/v1/tasks/day-counts")
    public DayCountsResponse dayCounts(@AuthenticationPrincipal Jwt jwt,
                                       @Parameter(description = "Ngày đầu (yyyy-MM-dd, gồm)")
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @Parameter(description = "Ngày cuối (yyyy-MM-dd, gồm)")
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return taskDayCountService.count(CurrentUser.id(jwt), from, to);
    }
}
