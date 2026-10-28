package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.dto.auth.AuthResponse;
import com.taskly.entity.IdentityProvider;
import com.taskly.entity.User;
import com.taskly.integration.facebook.FacebookClient;
import com.taskly.security.AppleIdTokenVerifier;
import com.taskly.security.ExternalIdentity;
import com.taskly.security.ExternalServiceUnavailableException;
import com.taskly.security.InvalidExternalTokenException;
import com.taskly.security.ProviderLinkTokens;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đăng nhập + liên kết Apple / Facebook (M13b) theo ĐÚNG bảng liên kết Google của spec 12.1 (GoogleIdentityService, M5):
 * 1) subject đã liên kết → đăng nhập (bật 2FA → 401 two-factor-required);
 * 2) không có email (Facebook) → 403; email chưa xác minh → 403;
 * 3) email trùng tài khoản có sẵn → 409 link-required + linkToken (KHÔNG tự liên kết);
 * 4) còn lại → tạo tài khoản không mật khẩu + workspace + Inbox + liên kết.
 * Mỗi method public là MỘT transaction. KHÔNG log token.
 */
@Service
@Transactional
public class ExternalIdentityService {

    private static final Logger log = LoggerFactory.getLogger(ExternalIdentityService.class);
    static final int DISPLAY_NAME_MAX_CODE_POINTS = 100;

    private final AppleIdTokenVerifier appleVerifier;
    private final FacebookClient facebookClient;
    private final ProviderLinkTokens linkTokens;
    private final UserService userService;
    private final AuthService authService;

    public ExternalIdentityService(AppleIdTokenVerifier appleVerifier, FacebookClient facebookClient,
                                   ProviderLinkTokens linkTokens, UserService userService, AuthService authService) {
        this.appleVerifier = appleVerifier;
        this.facebookClient = facebookClient;
        this.linkTokens = linkTokens;
        this.userService = userService;
        this.authService = authService;
    }

    /** POST auth/apple. displayNameHint = tên Apple gửi ở lần cho phép đầu (tuỳ chọn). */
    public AuthResponse signInWithApple(String idToken, String displayNameHint) {
        ExternalIdentity apple = verify(IdentityProvider.APPLE, idToken, IdentityErrors::invalidToken);
        return signIn(IdentityProvider.APPLE, apple, displayNameHint);
    }

    /** POST auth/facebook. */
    public AuthResponse signInWithFacebook(String accessToken) {
        ExternalIdentity facebook = verify(IdentityProvider.FACEBOOK, accessToken, IdentityErrors::invalidToken);
        return signIn(IdentityProvider.FACEBOOK, facebook, null);
    }

    /** POST me/identities/{apple|facebook}: token = idToken Apple / accessToken Facebook; đúng một trong token / linkToken. */
    public void link(UUID userId, IdentityProvider provider, String token, String linkToken) {
        User user = userService.requireUser(userId);
        ExternalIdentity identity = resolveForLink(user, provider, token, linkToken);
        Optional<User> owner = userService.findByIdentity(provider, identity.subject());
        if (owner.isPresent()) {
            if (owner.get().getId().equals(user.getId())) {
                return; // gửi lại đúng liên kết cũ → idempotent
            }
            throw IdentityErrors.linkedElsewhere();
        }
        if (user.identity(provider).isPresent()) {
            throw IdentityErrors.accountAlreadyLinked();
        }
        user.linkIdentity(provider, identity.subject(), identity.email());
    }

    /** DELETE me/identities/{apple|facebook}: chưa liên kết → 404; chưa có mật khẩu → 409 password-required. */
    public void unlink(UUID userId, IdentityProvider provider) {
        User user = userService.requireUser(userId);
        if (user.identity(provider).isEmpty()) {
            throw IdentityErrors.notLinked();
        }
        if (!user.hasPassword()) {
            throw IdentityErrors.passwordRequiredBeforeUnlink();
        }
        user.unlinkIdentity(provider);
    }

    private AuthResponse signIn(IdentityProvider provider, ExternalIdentity identity, String displayNameHint) {
        Optional<User> linked = userService.findByIdentity(provider, identity.subject());
        if (linked.isPresent()) {
            return authService.completeSignIn(linked.get());
        }
        if (identity.email() == null) {
            throw IdentityErrors.emailMissing();
        }
        if (!identity.emailVerified()) {
            throw IdentityErrors.emailNotVerified();
        }
        if (userService.findByEmail(identity.email()).isPresent()) {
            throw IdentityErrors.linkRequired(linkTokens.issue(provider, identity));
        }
        User user = authService.createAccount(identity.email(), null, displayName(provider, identity, displayNameHint));
        user.linkIdentity(provider, identity.subject(), identity.email());
        return authService.issueTokens(user);
    }

    private ExternalIdentity resolveForLink(User user, IdentityProvider provider, String token, String linkToken) {
        boolean hasToken = token != null && !token.isBlank();
        boolean hasLinkToken = linkToken != null && !linkToken.isBlank();
        if (hasToken == hasLinkToken) {
            throw IdentityErrors.exactlyOneToken();
        }
        if (hasToken) {
            ExternalIdentity identity = verify(provider, token, IdentityErrors::invalidTokenForLink);
            // Facebook không cấp email vẫn liên kết được (đã biết tài khoản nào); có email thì phải đã xác minh.
            if (identity.email() != null && !identity.emailVerified()) {
                throw IdentityErrors.emailNotVerified();
            }
            return identity;
        }
        ExternalIdentity identity;
        try {
            identity = linkTokens.verify(provider, linkToken);
        } catch (InvalidExternalTokenException ex) {
            throw IdentityErrors.invalidTokenForLink();
        }
        if (!identity.email().equals(user.getEmail())) {
            throw IdentityErrors.linkTokenForAnotherAccount();
        }
        return identity;
    }

    private ExternalIdentity verify(IdentityProvider provider, String token, Supplier<ApiException> whenInvalid) {
        Function<String, ExternalIdentity> verifier = switch (provider) {
            case APPLE -> appleVerifier::verify;
            case FACEBOOK -> facebookClient::verifyAccessToken;
            case GOOGLE -> throw new IllegalArgumentException("Google dùng GoogleIdentityService");
        };
        try {
            return verifier.apply(token);
        } catch (InvalidExternalTokenException ex) {
            throw whenInvalid.get();
        } catch (ExternalServiceUnavailableException ex) {
            log.warn("Không xác minh được token {}: {}", provider,
                    ex.getCause() == null ? "unknown" : ex.getCause().getClass().getSimpleName());
            throw IdentityErrors.unavailable();
        }
    }

    /** Tên hiển thị: tên app gửi → tên của nhà cung cấp → phần trước @ của email → "Apple" / "Facebook"; ≤ 100 code point. */
    static String displayName(IdentityProvider provider, ExternalIdentity identity, String hint) {
        String name = firstNonBlank(hint, identity.name());
        if (name == null && identity.email() != null) {
            int at = identity.email().indexOf('@');
            name = firstNonBlank(at > 0 ? identity.email().substring(0, at) : identity.email());
        }
        if (name == null) {
            name = provider == IdentityProvider.APPLE ? "Apple" : "Facebook";
        }
        if (name.codePointCount(0, name.length()) <= DISPLAY_NAME_MAX_CODE_POINTS) {
            return name;
        }
        return name.substring(0, name.offsetByCodePoints(0, DISPLAY_NAME_MAX_CODE_POINTS));
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }
}
