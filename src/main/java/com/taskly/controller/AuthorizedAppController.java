package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.oauth.AuthorizedAppResponse;
import com.taskly.security.CurrentUser;
import com.taskly.service.AuthorizedAppService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Ứng dụng đã được cấp quyền vào tài khoản (Cài đặt, Tích hợp): xem và thu hồi (M13c, F4). */
@RestController
@RequestMapping("/api/v1/me/authorized-apps")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class AuthorizedAppController {

    private final AuthorizedAppService authorizedApps;

    public AuthorizedAppController(AuthorizedAppService authorizedApps) {
        this.authorizedApps = authorizedApps;
    }

    @GetMapping
    public List<AuthorizedAppResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return authorizedApps.list(CurrentUser.id(jwt));
    }

    @DeleteMapping("/{clientId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable String clientId) {
        authorizedApps.revoke(CurrentUser.id(jwt), clientId);
    }
}
