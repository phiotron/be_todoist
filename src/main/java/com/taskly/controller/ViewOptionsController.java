package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.display.PutViewOptionsRequest;
import com.taskly.dto.display.ViewOptionsResponse;
import com.taskly.security.CurrentUser;
import com.taskly.service.ViewOptionsService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/view-options")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class ViewOptionsController {

    private final ViewOptionsService viewOptionsService;

    public ViewOptionsController(ViewOptionsService viewOptionsService) {
        this.viewOptionsService = viewOptionsService;
    }

    @GetMapping
    public List<ViewOptionsResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return viewOptionsService.list(CurrentUser.id(jwt));
    }

    @PutMapping("/{viewKey}")
    public ViewOptionsResponse put(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "TODAY | UPCOMING | project:<uuid> | label:<uuid> | filter:<uuid>")
            @PathVariable String viewKey,
            @Valid @RequestBody PutViewOptionsRequest request) {
        return viewOptionsService.put(CurrentUser.id(jwt), viewKey, request);
    }

    @DeleteMapping("/{viewKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@AuthenticationPrincipal Jwt jwt, @PathVariable String viewKey) {
        viewOptionsService.reset(CurrentUser.id(jwt), viewKey);
    }
}
