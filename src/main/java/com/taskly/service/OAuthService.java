package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.common.error.ProblemCodes;
import com.taskly.dto.oauth.OAuthAuthorizePreviewResponse;
import com.taskly.dto.oauth.OAuthAuthorizeRequest;
import com.taskly.dto.oauth.OAuthAuthorizeResponse;
import com.taskly.dto.oauth.OAuthTokenResponse;
import com.taskly.repository.DeveloperAppRepository;
import com.taskly.repository.DeveloperAppRepository.AppRow;
import com.taskly.repository.OAuthRepository;
import com.taskly.repository.OAuthRepository.CodeRow;
import com.taskly.security.OAuthAwareJwtDecoder;
import com.taskly.security.OAuthScopes;
import com.taskly.security.OpaqueTokens;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OAuth 2.0 authorization code + PKCE S256 cho ứng dụng bên thứ ba (spec 3.2 M13c, plan F2). Mã ủy quyền và token chỉ
 * lưu SHA-256; mã sống 10 phút và dùng một lần (dùng lại → 400 + thu hồi token đã cấp từ mã đó).
 */
@Service
@Transactional
public class OAuthService {

    static final Duration CODE_TTL = Duration.ofMinutes(10);
    private static final Pattern CHALLENGE = Pattern.compile("[A-Za-z0-9_-]{43,128}");
    private static final Pattern VERIFIER = Pattern.compile("[A-Za-z0-9._~-]{43,128}");

    private final DeveloperAppRepository apps;
    private final OAuthRepository oauth;
    private final Clock clock;

    public OAuthService(DeveloperAppRepository apps, OAuthRepository oauth, Clock clock) {
        this.apps = apps;
        this.oauth = oauth;
        this.clock = clock;
    }

    /** Màn đồng ý: kiểm client / redirect / scope rồi trả tên app + quyền để hiện. Sai → 400 (app KHÔNG tự redirect). */
    @Transactional(readOnly = true)
    public OAuthAuthorizePreviewResponse preview(String clientId, String redirectUri, String scope) {
        AppRow app = validatedApp(clientId, redirectUri);
        Set<String> scopes = OAuthScopes.parseRequested(scope);
        return new OAuthAuthorizePreviewResponse(app.clientId(), app.name(), app.redirectUri(), List.copyOf(scopes));
    }

    /** Người dùng bấm Cho phép: sinh mã dùng một lần, trả URL để app mở (redirect_uri?code=...&state=...). */
    public OAuthAuthorizeResponse authorize(UUID userId, OAuthAuthorizeRequest request) {
        if (request == null) {
            throw ApiException.badRequest(ErrorMessage.OAUTH_REQUEST_INVALID).withCode(ProblemCodes.OAUTH_INVALID_REQUEST);
        }
        AppRow app = validatedApp(request.clientId(), request.redirectUri());
        Set<String> scopes = OAuthScopes.parseRequested(request.scope());
        if (!"S256".equals(request.codeChallengeMethod()) || request.codeChallenge() == null
                || !CHALLENGE.matcher(request.codeChallenge()).matches()) {
            throw ApiException.badRequest(ErrorMessage.OAUTH_PKCE_INVALID).withCode(ProblemCodes.OAUTH_INVALID_REQUEST);
        }
        String code = OpaqueTokens.generate();
        Instant now = clock.instant();
        oauth.insertCode(UUID.randomUUID(), app.id(), userId, OpaqueTokens.sha256Hex(code), app.redirectUri(),
                OAuthScopes.toStored(scopes), request.codeChallenge(), now.plus(CODE_TTL), now);
        StringBuilder url = new StringBuilder(app.redirectUri())
                .append(app.redirectUri().contains("?") ? '&' : '?')
                .append("code=").append(URLEncoder.encode(code, StandardCharsets.UTF_8));
        if (request.state() != null && !request.state().isEmpty()) {
            url.append("&state=").append(URLEncoder.encode(request.state(), StandardCharsets.UTF_8));
        }
        return new OAuthAuthorizeResponse(url.toString());
    }

    /**
     * Đổi mã lấy token (endpoint công khai, xác thực bằng client_id + client_secret + code_verifier). noRollbackFor:
     * việc đốt mã dùng một lần và thu hồi token khi mã bị dùng lại phải còn lại dù request trả lỗi.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public OAuthTokenResponse exchange(String grantType, String code, String redirectUri, String clientId,
                                       String clientSecret, String codeVerifier) {
        if (!"authorization_code".equals(grantType)) {
            throw ApiException.badRequest(ErrorMessage.OAUTH_GRANT_TYPE_UNSUPPORTED)
                    .withCode(ProblemCodes.OAUTH_UNSUPPORTED_GRANT_TYPE).withProperty("error", "unsupported_grant_type");
        }
        AppRow app = authenticateClient(clientId, clientSecret);
        if (isBlank(code) || isBlank(redirectUri) || isBlank(codeVerifier)) {
            throw invalidRequest();
        }
        Instant now = clock.instant();
        CodeRow row = oauth.findCodeByHash(OpaqueTokens.sha256Hex(code)).orElseThrow(OAuthService::invalidGrant);
        if (!row.apiClientId().equals(app.id())) {
            throw invalidGrant();
        }
        if (row.usedAt() != null || !oauth.markCodeUsed(row.id(), now)) {
            oauth.revokeByCode(row.id(), now); // RFC 6749, mục 4.1.2: mã dùng lại, thu hồi token đã cấp
            throw invalidGrant();
        }
        if (!row.expiresAt().isAfter(now) || !row.redirectUri().equals(redirectUri)
                || !VERIFIER.matcher(codeVerifier).matches() || !pkceMatches(codeVerifier, row.codeChallenge())) {
            throw invalidGrant();
        }
        String token = OAuthAwareJwtDecoder.TOKEN_PREFIX + OpaqueTokens.generate();
        oauth.insertToken(UUID.randomUUID(), app.id(), row.userId(), OpaqueTokens.sha256Hex(token), row.scopes(),
                row.id(), now);
        return new OAuthTokenResponse(token, "Bearer", row.scopes());
    }

    /** RFC 7009: thu hồi token của chính app; token lạ vẫn coi như xong (không lộ token có tồn tại). */
    public void revoke(String clientId, String clientSecret, String token) {
        AppRow app = authenticateClient(clientId, clientSecret);
        if (!isBlank(token)) {
            oauth.revokeByHash(app.id(), OpaqueTokens.sha256Hex(token), clock.instant());
        }
    }

    private AppRow validatedApp(String clientId, String redirectUri) {
        if (isBlank(clientId) || isBlank(redirectUri)) {
            throw invalidRequest();
        }
        AppRow app = apps.findByClientId(clientId)
                .orElseThrow(() -> ApiException.badRequest(ErrorMessage.OAUTH_CLIENT_INVALID)
                        .withCode(ProblemCodes.OAUTH_INVALID_CLIENT));
        if (!app.redirectUri().equals(redirectUri)) {
            throw ApiException.badRequest(ErrorMessage.OAUTH_REDIRECT_URI_MISMATCH)
                    .withCode(ProblemCodes.OAUTH_INVALID_REQUEST);
        }
        return app;
    }

    private AppRow authenticateClient(String clientId, String clientSecret) {
        AppRow app = isBlank(clientId) || isBlank(clientSecret) ? null : apps.findByClientId(clientId).orElse(null);
        // So sánh hằng thời gian; app không tồn tại vẫn so với một băm giả để thời gian phản hồi không lộ client_id.
        String expected = app == null ? OpaqueTokens.sha256Hex("") : app.clientSecretHash();
        String given = OpaqueTokens.sha256Hex(clientSecret == null ? "" : clientSecret);
        boolean same = MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                given.getBytes(StandardCharsets.UTF_8));
        if (app == null || !same) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorMessage.OAUTH_CLIENT_AUTH_FAILED)
                    .withCode(ProblemCodes.OAUTH_INVALID_CLIENT).withProperty("error", "invalid_client");
        }
        return app;
    }

    static boolean pkceMatches(String verifier, String challenge) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    challenge.getBytes(StandardCharsets.US_ASCII));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM không hỗ trợ SHA-256", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static ApiException invalidRequest() {
        return ApiException.badRequest(ErrorMessage.OAUTH_REQUEST_INVALID).withCode(ProblemCodes.OAUTH_INVALID_REQUEST)
                .withProperty("error", "invalid_request");
    }

    private static ApiException invalidGrant() {
        return ApiException.badRequest(ErrorMessage.OAUTH_GRANT_INVALID).withCode(ProblemCodes.OAUTH_INVALID_GRANT)
                .withProperty("error", "invalid_grant");
    }
}
