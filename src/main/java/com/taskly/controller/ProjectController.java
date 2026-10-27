package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.project.CreateProjectRequest;
import com.taskly.dto.project.MoveProjectRequest;
import com.taskly.dto.project.ProjectResponse;
import com.taskly.dto.project.UpdateProjectRequest;
import com.taskly.security.CurrentUser;
import com.taskly.service.ProjectService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    /** Toàn bộ dự án (không phân trang); mặc định chưa lưu trữ. Inbox đứng đầu, rồi theo cây. */
    @GetMapping
    public List<ProjectResponse> list(@AuthenticationPrincipal Jwt jwt,
                                      @RequestParam(defaultValue = "false") boolean archived) {
        return projectService.list(CurrentUser.id(jwt), archived);
    }

    /** 201 khi tạo mới; 200 + Idempotent-Replay: true khi `id` client đã có cùng nội dung (spec 12.11). */
    @PostMapping
    @Parameter(in = ParameterIn.HEADER, name = "Idempotency-Key", description = "Chống gửi trùng (UUID v4)",
            schema = @Schema(type = "string", maxLength = 64))
    @ApiResponse(responseCode = "201", description = "Đã tạo")
    @ApiResponse(responseCode = "200", description = "`id` client đã có cùng nội dung — kèm header Idempotent-Replay: true")
    public ResponseEntity<ProjectResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                  @Valid @RequestBody CreateProjectRequest request) {
        return projectService.create(CurrentUser.id(jwt), request).toResponseEntity();
    }

    @GetMapping("/{projectId}")
    public ProjectResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {
        return projectService.get(CurrentUser.id(jwt), projectId);
    }

    @PatchMapping("/{projectId}")
    public ProjectResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId,
                                  @Valid @RequestBody UpdateProjectRequest request) {
        return projectService.update(CurrentUser.id(jwt), projectId, request);
    }

    @PutMapping("/{projectId}/favorite")
    public ProjectResponse favorite(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {
        return projectService.setFavorite(CurrentUser.id(jwt), projectId, true);
    }

    @DeleteMapping("/{projectId}/favorite")
    public ProjectResponse unfavorite(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {
        return projectService.setFavorite(CurrentUser.id(jwt), projectId, false);
    }

    /** Xoá vĩnh viễn kèm dự án con, section, task (app hỏi xác nhận trước — BR-11). */
    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {
        projectService.delete(CurrentUser.id(jwt), projectId);
    }

    @PostMapping("/{projectId}/move")
    public ProjectResponse move(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId,
                                @Valid @RequestBody MoveProjectRequest request) {
        return projectService.move(CurrentUser.id(jwt), projectId, request);
    }

    @PostMapping("/{projectId}/archive")
    public ProjectResponse archive(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {
        return projectService.archive(CurrentUser.id(jwt), projectId);
    }

    @PostMapping("/{projectId}/unarchive")
    public ProjectResponse unarchive(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {
        return projectService.unarchive(CurrentUser.id(jwt), projectId);
    }
}
