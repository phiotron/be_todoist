package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.project.ProjectResponse;
import com.taskly.security.CurrentUser;
import com.taskly.service.ProjectDuplicateService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** M11 F1: nhân bản dự án (tên "Copy of …" theo Accept-Language). */
@RestController
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class ProjectDuplicateController {

    private final ProjectDuplicateService projectDuplicateService;

    public ProjectDuplicateController(ProjectDuplicateService projectDuplicateService) {
        this.projectDuplicateService = projectDuplicateService;
    }

    @PostMapping("/api/v1/projects/{projectId}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    @Parameter(in = ParameterIn.HEADER, name = "Idempotency-Key", description = "Chống gửi trùng (UUID v4)",
            schema = @Schema(type = "string", maxLength = 64))
    public ProjectResponse duplicate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId,
                                     @Parameter(hidden = true) Locale locale) {
        return projectDuplicateService.duplicate(CurrentUser.id(jwt), projectId, locale);
    }
}
