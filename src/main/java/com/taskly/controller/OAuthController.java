package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.oauth.OAuthAuthorizePreviewResponse;
import com.taskly.dto.oauth.OAuthAuthorizeRequest;
import com.taskly.dto.oauth.OAuthAuthorizeResponse;
import com.taskly.dto.oauth.OAuthTokenResponse;
import com.taskly.security.CurrentUser;
import com.taskly.security.OAuthRateLimiter;
import com.taskly.service.OAuthService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OAuth cho ứng dụng bên thứ ba (M13c, F2). authorize / preview cần token của app Taskly (người dùng đang đăng
 * nhập); token / revoke là công khai, xác thực bằng client_id + client_secret trong form (RFC 6749 / 7009).
 */
@RestController
@RequestMapping("/api/v1/oauth")
public class OAuthController {

    private final OAuthService oauth;
    private final OAuthRateLimiter rateLimiter;

    public OAuthController(OAuthService oauth, OAuthRateLimiter rateLimiter) {
        this.oauth = oauth;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/authorize/preview")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    public OAuthAuthorizePreviewResponse preview(@RequestParam(required = false) String clientId,
                                                 @RequestParam(required = false) String redirectUri,
                                                 @RequestParam(required = false) String scope) {
        return oauth.preview(clientId, redirectUri, scope);
    }

    @PostMapping("/authorize")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    public OAuthAuthorizeResponse authorize(@AuthenticationPrincipal Jwt jwt, @RequestBody OAuthAuthorizeRequest request) {
        return oauth.authorize(CurrentUser.id(jwt), request);
    }

    /** Đổi mã lấy token. Form: grant_type, code, redirect_uri, client_id, client_secret, code_verifier. */
    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<OAuthTokenResponse> token(
            HttpServletRequest httpRequest,
            @RequestParam(name = "grant_type", required = false) String grantType,
            @RequestParam(required = false) String code,
            @RequestParam(name = "redirect_uri", required = false) String redirectUri,
            @RequestParam(name = "client_id", required = false) String clientId,
            @RequestParam(name = "client_secret", required = false) String clientSecret,
            @RequestParam(name = "code_verifier", required = false) String codeVerifier) {
        rateLimiter.check(httpRequest);
        OAuthTokenResponse body = oauth.exchange(grantType, code, redirectUri, clientId, clientSecret, codeVerifier);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    /** Thu hồi token của chính app; luôn 200 (RFC 7009) trừ khi xác thực app sai (401). */
    @PostMapping(value = "/revoke", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public void revoke(HttpServletRequest httpRequest,
                       @RequestParam(name = "client_id", required = false) String clientId,
                       @RequestParam(name = "client_secret", required = false) String clientSecret,
                       @RequestParam(required = false) String token) {
        rateLimiter.check(httpRequest);
        oauth.revoke(clientId, clientSecret, token);
    }
}
