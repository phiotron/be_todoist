package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.user.UserResponse;
import com.taskly.security.CurrentUser;
import com.taskly.service.AvatarService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Ảnh đại diện (M8b, plan F6): JPEG / PNG ≤ 4 MB, multipart trường "file". */
@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class AvatarController {

    private final AvatarService avatarService;

    public AvatarController(AvatarService avatarService) {
        this.avatarService = avatarService;
    }

    @PostMapping(path = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UserResponse upload(@AuthenticationPrincipal Jwt jwt,
                               @RequestPart(name = "file", required = false) MultipartFile file) {
        return avatarService.upload(CurrentUser.id(jwt), file);
    }

    @DeleteMapping("/me/avatar")
    public UserResponse remove(@AuthenticationPrincipal Jwt jwt) {
        return avatarService.remove(CurrentUser.id(jwt));
    }

    /** Ảnh của người dùng bất kỳ (cần đăng nhập). Không có → 404. Đổi ảnh → avatarUrl đổi ?v= nên cache dài được. */
    @GetMapping("/users/{userId}/avatar")
    public ResponseEntity<InputStreamResource> avatar(@PathVariable UUID userId) {
        AvatarService.Avatar avatar = avatarService.open(userId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(avatar.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400")
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(avatar.content()));
    }
}
