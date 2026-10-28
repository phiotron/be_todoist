package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.section.MoveSectionRequest;
import com.taskly.dto.section.SectionResponse;
import com.taskly.security.CurrentUser;
import com.taskly.service.SectionTransferService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** M11 F2 nhân bản section, F3 Move section sang dự án khác (Reorder trong dự án: POST reorder của M1). */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/sections/{sectionId}")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class SectionTransferController {

    private final SectionTransferService sectionTransferService;

    public SectionTransferController(SectionTransferService sectionTransferService) {
        this.sectionTransferService = sectionTransferService;
    }

    @PostMapping("/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    @Parameter(in = ParameterIn.HEADER, name = "Idempotency-Key", description = "Chống gửi trùng (UUID v4)",
            schema = @Schema(type = "string", maxLength = 64))
    public SectionResponse duplicate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId,
                                     @PathVariable UUID sectionId) {
        return sectionTransferService.duplicate(CurrentUser.id(jwt), projectId, sectionId);
    }

    @PostMapping("/move")
    public SectionResponse move(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId,
                                @PathVariable UUID sectionId, @Valid @RequestBody MoveSectionRequest request) {
        return sectionTransferService.move(CurrentUser.id(jwt), projectId, sectionId, request);
    }
}
