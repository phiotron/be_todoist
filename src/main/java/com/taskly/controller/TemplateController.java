package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.project.ProjectResponse;
import com.taskly.dto.template.ApplyTemplateRequest;
import com.taskly.dto.template.LibraryTemplateResponse;
import com.taskly.dto.template.SaveTemplateRequest;
import com.taskly.dto.template.TemplateDetailResponse;
import com.taskly.dto.template.TemplateResponse;
import com.taskly.security.CurrentUser;
import com.taskly.service.TemplateService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

/** M11 F5–F7: template của mình + thư viện dựng sẵn (chữ theo Accept-Language). */
@RestController
@RequestMapping("/api/v1/templates")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class TemplateController {

    private final TemplateService templateService;

    public TemplateController(TemplateService templateService) {
        this.templateService = templateService;
    }

    /** My templates, mới nhất trước (≤ 100, không phân trang). */
    @GetMapping
    public List<TemplateResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return templateService.list(CurrentUser.id(jwt));
    }

    /** Save as template. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Parameter(in = ParameterIn.HEADER, name = "Idempotency-Key", description = "Chống gửi trùng (UUID v4)",
            schema = @Schema(type = "string", maxLength = 64))
    public TemplateResponse save(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SaveTemplateRequest request) {
        return templateService.save(CurrentUser.id(jwt), request);
    }

    @GetMapping("/library")
    public List<LibraryTemplateResponse> library(@Parameter(hidden = true) Locale locale) {
        return templateService.library(locale);
    }

    @GetMapping("/library/{key}")
    public TemplateDetailResponse libraryDetail(@PathVariable String key, @Parameter(hidden = true) Locale locale) {
        return templateService.libraryDetail(key, locale);
    }

    @PostMapping("/library/{key}/apply")
    @Parameter(in = ParameterIn.HEADER, name = "Idempotency-Key", description = "Chống gửi trùng (UUID v4)",
            schema = @Schema(type = "string", maxLength = 64))
    @ApiResponse(responseCode = "201", description = "Đã tạo dự án mới từ template")
    @ApiResponse(responseCode = "200", description = "Đã thêm nội dung template vào dự án projectId")
    public ResponseEntity<ProjectResponse> applyLibrary(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                                                        @Valid @RequestBody ApplyTemplateRequest request,
                                                        @Parameter(hidden = true) Locale locale) {
        return toEntity(templateService.applyLibrary(CurrentUser.id(jwt), key, request, locale));
    }

    @GetMapping("/{templateId}")
    public TemplateDetailResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID templateId) {
        return templateService.get(CurrentUser.id(jwt), templateId);
    }

    @DeleteMapping("/{templateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID templateId) {
        templateService.delete(CurrentUser.id(jwt), templateId);
    }

    @PostMapping("/{templateId}/apply")
    @Parameter(in = ParameterIn.HEADER, name = "Idempotency-Key", description = "Chống gửi trùng (UUID v4)",
            schema = @Schema(type = "string", maxLength = 64))
    @ApiResponse(responseCode = "201", description = "Đã tạo dự án mới từ template")
    @ApiResponse(responseCode = "200", description = "Đã thêm nội dung template vào dự án projectId")
    public ResponseEntity<ProjectResponse> apply(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID templateId,
                                                 @Valid @RequestBody ApplyTemplateRequest request) {
        return toEntity(templateService.applyMine(CurrentUser.id(jwt), templateId, request));
    }

    private static ResponseEntity<ProjectResponse> toEntity(TemplateService.ApplyResult result) {
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.project());
    }
}
