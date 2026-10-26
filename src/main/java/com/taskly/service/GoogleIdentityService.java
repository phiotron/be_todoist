package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.dto.auth.AuthResponse;
import com.taskly.entity.IdentityProvider;
import com.taskly.entity.User;
import com.taskly.security.GoogleIdTokenVerifier;
import com.taskly.security.GoogleIdentity;
import com.taskly.security.GoogleLinkTokens;
import com.taskly.security.InvalidGoogleTokenException;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đăng nhập Google và liên kết tài khoản (M5, spec 12.1). Mỗi method public là MỘT transaction: tạo user + workspace
 * + Inbox + liên kết + refresh token cùng thành công hoặc cùng rollback. KHÔNG log token.
 */
@Service
@Transactional
public class GoogleIdentityService {

    static final int DISPLAY_NAME_MAX_CODE_POINTS = 100;

    private final GoogleIdTokenVerifier idTokenVerifier;
    private final GoogleLinkTokens linkTokens;
    private final UserService userService;
    private final AuthService authService;

    public GoogleIdentityService(GoogleIdTokenVerifier idTokenVerifier, GoogleLinkTokens linkTokens,
                                 UserService userService, AuthService authService) {
        this.idTokenVerifier = idTokenVerifier;
        this.linkTokens = linkTokens;
        this.userService = userService;
        this.authService = authService;
    }

    /** POST auth/google. */
    public AuthResponse signIn(String idToken) {
        GoogleIdentity google = verifyIdToken(idToken, GoogleAuthErrors::invalidIdToken);
        Optional<User> linked = userService.findByIdentity(IdentityProvider.GOOGLE, google.subject());
        if (linked.isPresent()) {
            return authService.issueTokens(linked.get());                                    // tình huống 4
        }
        User user = authService.createAccount(google.email(), null, displayName(google));    // tình huống 1
        user.linkIdentity(IdentityProvider.GOOGLE, google.subject(), google.email());
        return authService.issueTokens(user);
    }

    private GoogleIdentity verifyIdToken(String idToken, Supplier<ApiException> whenInvalid) {
        try {
            return idTokenVerifier.verify(idToken);
        } catch (InvalidGoogleTokenException ex) {
            throw whenInvalid.get();
        }
    }

    /** Tên hiển thị: name của Google → phần trước @ của email → "Google"; tối đa 100 code point. */
    static String displayName(GoogleIdentity google) {
        String name = google.name() == null ? "" : google.name().strip();
        if (name.isEmpty()) {
            int at = google.email().indexOf('@');
            name = (at > 0 ? google.email().substring(0, at) : google.email()).strip();
        }
        if (name.isEmpty()) {
            name = "Google";
        }
        if (name.codePointCount(0, name.length()) <= DISPLAY_NAME_MAX_CODE_POINTS) {
            return name;
        }
        return name.substring(0, name.offsetByCodePoints(0, DISPLAY_NAME_MAX_CODE_POINTS));
    }
}
