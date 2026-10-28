package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.activity.ActivityResponse;
import com.taskly.dto.common.Page;
import com.taskly.security.CurrentUser;
import com.taskly.service.ActivityQueryService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Activity log (M10 plan F5): GET /api/v1/activity — View activity của task / dự án / Inbox, tab Activity log. */
@RestController
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class ActivityController {

    private final ActivityQueryService activityQueryService;

    public ActivityController(ActivityQueryService activityQueryService) {
        this.activityQueryService = activityQueryService;
    }

    @GetMapping("/api/v1/activity")
    public Page<ActivityResponse> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) UUID taskId,
            @Parameter(schema = @Schema(allowableValues = {"task", "project", "section"}))
            @RequestParam(required = false) String objectType,
            @Parameter(schema = @Schema(allowableValues = {"added", "updated", "completed", "uncompleted", "deleted",
                    "archived", "unarchived"}))
            @RequestParam(required = false) String eventType,
            @Parameter(description = "Người làm (dữ liệu cộng tác có từ M8)")
            @RequestParam(required = false) UUID initiatorId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return activityQueryService.list(CurrentUser.id(jwt), new ActivityQueryService.Filter(projectId, taskId,
                objectType, eventType, initiatorId, cursor, limit));
    }
}
