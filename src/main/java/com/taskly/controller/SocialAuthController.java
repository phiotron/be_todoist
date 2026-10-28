package com.taskly.controller;

import com.taskly.common.web.DeepLinks;
import com.taskly.config.OpenApiConfig;
import com.taskly.dto.auth.AppleSignInRequest;
import com.taskly.dto.auth.AuthResponse;
import com.taskly.dto.auth.FacebookSignInRequest;
import com.taskly.dto.user.AppleLinkRequest;
import com.taskly.dto.user.FacebookLinkRequest;
import com.taskly.entity.IdentityProvider;
import com.taskly.integration.facebook.FacebookClient;
import com.taskly.security.AppleProperties;
import com.taskly.security.AuthRateLimiter;
import com.taskly.security.CurrentUser;
import com.taskly.security.ExternalServiceUnavailableException;
import com.taskly.security.FacebookProperties;
import com.taskly.security.InvalidExternalTokenException;
import com.taskly.service.ExternalIdentityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Đăng nhập / liên kết Apple và Facebook (M13b) — cùng hợp đồng với GoogleAuthController (M5). Trên Android dùng luồng web:
 * trình duyệt → nhà cung cấp → callback ở đây → 303 về deep link taskly://oauth/{apple|facebook} → app gọi auth/* hoặc
 * me/identities/*. Callback KHÔNG đăng nhập ai: chỉ chuyển tiếp token cho app (app kiểm state).
 */
@RestController
@RequestMapping("/api/v1")
public class SocialAuthController {

    static final String APPLE_RATE_LIMIT = "apple";
    static final String FACEBOOK_RATE_LIMIT = "facebook";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ExternalIdentityService identityService;
    private final AuthRateLimiter authRateLimiter;
    private final FacebookClient facebookClient;
    private final AppleProperties appleProperties;
    private final FacebookProperties facebookProperties;

    public SocialAuthController(ExternalIdentityService identityService, AuthRateLimiter authRateLimiter,
                                FacebookClient facebookClient, AppleProperties appleProperties,
                                FacebookProperties facebookProperties) {
        this.identityService = identityService;
        this.authRateLimiter = authRateLimiter;
        this.facebookClient = facebookClient;
        this.appleProperties = appleProperties;
        this.facebookProperties = facebookProperties;
    }

    // ---- Apple

    @Operation(summary = "Đăng nhập / đăng ký bằng Apple",
            description = "Xác minh ID token Apple (JWKS). 200 AuthResponse; 401 token sai / hết hạn; 401 code = "
                    + "\"two-factor-required\" kèm twoFactorToken; 403 email chưa xác minh; 409 code = \"link-required\" "
                    + "kèm linkToken (hạn 10 phút) khi email đã có tài khoản; 429; 503 không tải được khoá Apple.")
    @PostMapping("/auth/apple")
    public AuthResponse appleSignIn(@Valid @RequestBody AppleSignInRequest request, HttpServletRequest httpRequest) {
        authRateLimiter.check(APPLE_RATE_LIMIT, httpRequest);
        return identityService.signInWithApple(request.idToken(), request.displayName());
    }

    /**
     * redirect_uri của luồng web Apple (response_mode=form_post). Apple POST form (state, id_token, code, user, error) →
     * 303 về taskly://oauth/apple?state&idToken&name hoặc ?state&error=cancelled|failed.
     */
    @Operation(summary = "Callback luồng web Sign in with Apple (Apple gọi, không phải app)")
    @PostMapping(value = "/auth/apple/callback", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> appleCallback(@RequestParam(name = "state", required = false) String state,
                                              @RequestParam(name = "id_token", required = false) String idToken,
                                              @RequestParam(name = "user", required = false) String user,
                                              @RequestParam(name = "error", required = false) String error) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("state", state);
        if (error != null || idToken == null || idToken.isBlank()) {
            params.put("error", "user_cancelled_authorize".equals(error) ? "cancelled" : "failed");
        } else {
            params.put("idToken", idToken);
            params.put("name", appleName(user));
        }
        return DeepLinks.redirect(appleProperties.appRedirect(), params);
    }

    @Operation(summary = "Liên kết Apple vào tài khoản đang đăng nhập",
            description = "Gửi đúng một trong idToken hoặc linkToken. 204; 400 token không dùng được / email linkToken "
                    + "khác tài khoản; 403 email chưa xác minh; 409 Apple đã gắn tài khoản khác hoặc tài khoản đã gắn Apple khác.")
    @PostMapping("/me/identities/apple")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    public void linkApple(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AppleLinkRequest request) {
        identityService.link(CurrentUser.id(jwt), IdentityProvider.APPLE, request.idToken(), request.linkToken());
    }

    @Operation(summary = "Bỏ liên kết Apple", description = "204; 404 chưa liên kết; 409 code = \"password-required\".")
    @DeleteMapping("/me/identities/apple")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    public void unlinkApple(@AuthenticationPrincipal Jwt jwt) {
        identityService.unlink(CurrentUser.id(jwt), IdentityProvider.APPLE);
    }

    // ---- Facebook

    @Operation(summary = "Đăng nhập / đăng ký bằng Facebook",
            description = "Xác minh user access token qua Graph debug_token. 200 AuthResponse; 401 token sai / của app "
                    + "khác; 401 two-factor-required; 403 tài khoản Facebook không có email hoặc email chưa xác minh; "
                    + "409 link-required kèm linkToken; 429; 503 không gọi được Facebook / chưa cấu hình.")
    @PostMapping("/auth/facebook")
    public AuthResponse facebookSignIn(@Valid @RequestBody FacebookSignInRequest request,
                                       HttpServletRequest httpRequest) {
        authRateLimiter.check(FACEBOOK_RATE_LIMIT, httpRequest);
        return identityService.signInWithFacebook(request.accessToken());
    }

    /** redirect_uri của luồng web Facebook: đổi code lấy access token rồi 303 về taskly://oauth/facebook. */
    @Operation(summary = "Callback luồng web Facebook Login (Facebook gọi, không phải app)")
    @GetMapping("/auth/facebook/callback")
    public ResponseEntity<Void> facebookCallback(@RequestParam(name = "state", required = false) String state,
                                                 @RequestParam(name = "code", required = false) String code,
                                                 @RequestParam(name = "error", required = false) String error,
                                                 HttpServletRequest httpRequest) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("state", state);
        if (error != null || code == null || code.isBlank()) {
            params.put("error", "access_denied".equals(error) ? "cancelled" : "failed");
            return DeepLinks.redirect(facebookProperties.appRedirect(), params);
        }
        authRateLimiter.check(FACEBOOK_RATE_LIMIT, httpRequest);
        try {
            params.put("accessToken", facebookClient.exchangeCode(code, facebookProperties.redirectUri()));
        } catch (InvalidExternalTokenException | ExternalServiceUnavailableException ex) {
            params.put("error", "failed");
        }
        return DeepLinks.redirect(facebookProperties.appRedirect(), params);
    }

    @Operation(summary = "Liên kết Facebook vào tài khoản đang đăng nhập",
            description = "Gửi đúng một trong accessToken hoặc linkToken. 204; 400; 403; 409 như Apple.")
    @PostMapping("/me/identities/facebook")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    public void linkFacebook(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody FacebookLinkRequest request) {
        identityService.link(CurrentUser.id(jwt), IdentityProvider.FACEBOOK, request.accessToken(),
                request.linkToken());
    }

    @Operation(summary = "Bỏ liên kết Facebook", description = "204; 404 chưa liên kết; 409 code = \"password-required\".")
    @DeleteMapping("/me/identities/facebook")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    public void unlinkFacebook(@AuthenticationPrincipal Jwt jwt) {
        identityService.unlink(CurrentUser.id(jwt), IdentityProvider.FACEBOOK);
    }

    /** Tham số "user" của Apple (chỉ lần đầu): {"name":{"firstName":"…","lastName":"…"},"email":"…"} → "First Last". */
    static String appleName(String userJson) {
        if (userJson == null || userJson.isBlank()) {
            return null;
        }
        try {
            Map<?, ?> user = JSON.readValue(userJson, Map.class);
            if (!(user.get("name") instanceof Map<?, ?> name)) {
                return null;
            }
            String joined = (text(name.get("firstName")) + " " + text(name.get("lastName"))).strip();
            return joined.isEmpty() ? null : joined;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String text(Object value) {
        return value instanceof String s ? s.strip() : "";
    }
}
