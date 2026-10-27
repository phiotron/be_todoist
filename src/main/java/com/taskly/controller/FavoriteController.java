package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.favorite.FavoriteResponse;
import com.taskly.security.CurrentUser;
import com.taskly.service.FavoriteService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Khối Favorites của Browse (spec 12.2). Đặt / bỏ yêu thích ở endpoint của từng loại đích (projects/{id}/favorite…). */
@RestController
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class FavoriteController {

    private final FavoriteService favoriteService;

    public FavoriteController(FavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    @GetMapping("/api/v1/favorites")
    public List<FavoriteResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return favoriteService.list(CurrentUser.id(jwt));
    }
}
