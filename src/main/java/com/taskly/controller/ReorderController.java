package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.reorder.ReorderRequest;
import com.taskly.dto.reorder.ReorderResponse;
import com.taskly.security.CurrentUser;
import com.taskly.service.ReorderService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class ReorderController {

    private final ReorderService reorderService;

    public ReorderController(ReorderService reorderService) {
        this.reorderService = reorderService;
    }

    @PostMapping("/api/v1/reorder")
    public ReorderResponse reorder(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ReorderRequest request) {
        return reorderService.reorder(CurrentUser.id(jwt), request);
    }
}
