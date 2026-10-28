package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.productivity.ProductivityResponse;
import com.taskly.security.CurrentUser;
import com.taskly.service.ProductivityService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Màn Productivity (M10 plan F4): GET /api/v1/productivity — tab Daily / Weekly / Karma của người đang đăng nhập. */
@RestController
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class ProductivityController {

    private final ProductivityService productivityService;

    public ProductivityController(ProductivityService productivityService) {
        this.productivityService = productivityService;
    }

    @GetMapping("/api/v1/productivity")
    public ProductivityResponse get(@AuthenticationPrincipal Jwt jwt) {
        return productivityService.get(CurrentUser.id(jwt));
    }
}
