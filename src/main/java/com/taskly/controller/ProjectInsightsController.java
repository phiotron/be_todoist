package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.productivity.ProjectInsightsResponse;
import com.taskly.security.CurrentUser;
import com.taskly.service.ProjectInsightsService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Insights của dự án (M10 plan F6) — controller riêng để không sửa ProjectController của M1. */
@RestController
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class ProjectInsightsController {

    private final ProjectInsightsService projectInsightsService;

    public ProjectInsightsController(ProjectInsightsService projectInsightsService) {
        this.projectInsightsService = projectInsightsService;
    }

    @GetMapping("/api/v1/projects/{projectId}/insights")
    public ProjectInsightsResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {
        return projectInsightsService.get(CurrentUser.id(jwt), projectId);
    }
}
