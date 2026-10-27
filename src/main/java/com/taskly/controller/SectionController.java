package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.section.CreateSectionRequest;
import com.taskly.dto.section.SectionResponse;
import com.taskly.dto.section.UpdateSectionRequest;
import com.taskly.security.CurrentUser;
import com.taskly.service.SectionService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/sections")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class SectionController {

    private final SectionService sectionService;

    public SectionController(SectionService sectionService) {
        this.sectionService = sectionService;
    }

    @GetMapping
    public List<SectionResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {
        return sectionService.list(CurrentUser.id(jwt), projectId);
    }

    /** 201 khi tạo mới; 200 + Idempotent-Replay: true khi `id` client đã có cùng nội dung (spec 12.11). */
    @PostMapping
    @Parameter(in = ParameterIn.HEADER, name = "Idempotency-Key", description = "Chống gửi trùng (UUID v4)",
            schema = @Schema(type = "string", maxLength = 64))
    @ApiResponse(responseCode = "201", description = "Đã tạo")
    @ApiResponse(responseCode = "200", description = "`id` client đã có cùng nội dung — kèm header Idempotent-Replay: true")
    public ResponseEntity<SectionResponse> create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId,
                                                  @Valid @RequestBody CreateSectionRequest request) {
        return sectionService.create(CurrentUser.id(jwt), projectId, request).toResponseEntity();
    }

    @PatchMapping("/{sectionId}")
    public SectionResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId,
                                  @PathVariable UUID sectionId, @Valid @RequestBody UpdateSectionRequest request) {
        return sectionService.update(CurrentUser.id(jwt), projectId, sectionId, request);
    }

    /** Xoá vĩnh viễn kèm mọi task (app hỏi xác nhận, ghi số task — BR-11). */
    @DeleteMapping("/{sectionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId, @PathVariable UUID sectionId) {
        sectionService.delete(CurrentUser.id(jwt), projectId, sectionId);
    }
}
