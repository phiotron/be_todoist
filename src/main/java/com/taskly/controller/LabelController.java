package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.label.CreateLabelRequest;
import com.taskly.dto.label.LabelResponse;
import com.taskly.dto.label.UpdateLabelRequest;
import com.taskly.security.CurrentUser;
import com.taskly.service.LabelService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/labels")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class LabelController {

    private final LabelService labelService;

    public LabelController(LabelService labelService) {
        this.labelService = labelService;
    }

    @GetMapping
    public List<LabelResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return labelService.list(CurrentUser.id(jwt));
    }

    /** 201; `id` đã có + cùng nội dung → 200 kèm `Idempotent-Replay: true`; khác nội dung / của người khác → 409. */
    @PostMapping
    @Parameter(in = ParameterIn.HEADER, name = "Idempotency-Key", description = "Chống gửi trùng (UUID v4)",
            schema = @Schema(type = "string", maxLength = 64))
    @ApiResponse(responseCode = "201", description = "Đã tạo")
    @ApiResponse(responseCode = "200", description = "`id` client đã có cùng nội dung — kèm header Idempotent-Replay: true")
    public ResponseEntity<LabelResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                @RequestBody CreateLabelRequest request) {
        return labelService.create(CurrentUser.id(jwt), request).toResponseEntity();
    }

    @PatchMapping("/{labelId}")
    public LabelResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID labelId,
                                @RequestBody UpdateLabelRequest request) {
        return labelService.update(CurrentUser.id(jwt), labelId, request);
    }

    @DeleteMapping("/{labelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID labelId) {
        labelService.delete(CurrentUser.id(jwt), labelId);
    }

    @PutMapping("/{labelId}/favorite")
    public LabelResponse favorite(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID labelId) {
        return labelService.setFavorite(CurrentUser.id(jwt), labelId, true);
    }

    @DeleteMapping("/{labelId}/favorite")
    public LabelResponse unfavorite(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID labelId) {
        return labelService.setFavorite(CurrentUser.id(jwt), labelId, false);
    }
}
