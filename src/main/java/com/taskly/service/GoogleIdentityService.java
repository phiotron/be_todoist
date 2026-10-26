package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.dto.auth.AuthResponse;
import com.taskly.dto.user.GoogleLinkRequest;
import com.taskly.entity.IdentityProvider;
import com.taskly.entity.User;
import com.taskly.security.GoogleIdTokenVerifier;
import com.taskly.security.GoogleIdentity;
import com.taskly.security.GoogleLinkTokens;
import com.taskly.security.GoogleUnavailableException;
import com.taskly.security.InvalidGoogleTokenException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đăng nhập Google và liên kết tài khoản (M5, spec 12.1). Mỗi method public là MỘT transaction: tạo user + workspace
 * + Inbox + liên kết + refresh token cùng thành công hoặc cùng rollback; lỗi 401/403/409 không ghi gì. KHÔNG log token.
 */
@Service
@Transactional
public class GoogleIdentityService {

    private static final Logger log = LoggerFactory.getLogger(GoogleIdentityService.class);
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

    /**
     * POST auth/google. Thứ tự kiểm tra là cố ý:
     * 1) sub đã liên kết → đăng nhập (không dùng email nên không cần email_verified);
     * 2) email chưa xác minh → 403 (không dùng email đó để tạo hay liên kết — kể cả không phát linkToken);
     * 3) email trùng tài khoản có sẵn → 409 link-required (KHÔNG tự liên kết);
     * 4) còn lại → tạo tài khoản không mật khẩu + workspace + Inbox + liên kết.
     */
    public AuthResponse signIn(String idToken) {
        GoogleIdentity google = verifyIdToken(idToken, GoogleAuthErrors::invalidIdToken);    // tình huống 6
        Optional<User> linked = userService.findByIdentity(IdentityProvider.GOOGLE, google.subject());
        if (linked.isPresent()) {
            return authService.issueTokens(linked.get());                                    // tình huống 4
        }
        if (!google.emailVerified()) {
            throw GoogleAuthErrors.emailNotVerified();                                       // tình huống 5
        }
        if (userService.findByEmail(google.email()).isPresent()) {
            throw GoogleAuthErrors.linkRequired(linkTokens.issue(google));                   // tình huống 2
        }
        User user = authService.createAccount(google.email(), null, displayName(google));    // tình huống 1
        user.linkIdentity(IdentityProvider.GOOGLE, google.subject(), google.email());
        return authService.issueTokens(user);
    }

    /**
     * POST me/identities/google — liên kết Google vào tài khoản đang đăng nhập (controller trả 204). Gửi lại đúng
     * Google đã liên kết → không làm gì (204, idempotent). sub đã gắn tài khoản khác → 409; tài khoản đã gắn Google
     * khác → 409 (bỏ liên kết trước).
     */
    public void link(UUID userId, GoogleLinkRequest request) {
        User user = userService.requireUser(userId);
        GoogleIdentity google = resolveForLink(user, request);
        Optional<User> owner = userService.findByIdentity(IdentityProvider.GOOGLE, google.subject());
        if (owner.isPresent()) {
            if (owner.get().getId().equals(user.getId())) {
                return;
            }
            throw GoogleAuthErrors.googleAccountLinkedElsewhere();
        }
        if (user.identity(IdentityProvider.GOOGLE).isPresent()) {
            throw GoogleAuthErrors.accountAlreadyLinked();
        }
        user.linkIdentity(IdentityProvider.GOOGLE, google.subject(), google.email());
    }

    /** DELETE me/identities/google — chưa liên kết → 404; chưa có mật khẩu → 409; xong → controller trả 204. */
    public void unlink(UUID userId) {
        User user = userService.requireUser(userId);
        if (user.identity(IdentityProvider.GOOGLE).isEmpty()) {
            throw GoogleAuthErrors.googleNotLinked();
        }
        if (!user.hasPassword()) {
            throw GoogleAuthErrors.passwordRequiredBeforeUnlink();
        }
        user.unlinkIdentity(IdentityProvider.GOOGLE);
    }

    /** Chọn nguồn danh tính Google: idToken (Cài đặt) hoặc linkToken (sau 409 link-required). */
    private GoogleIdentity resolveForLink(User user, GoogleLinkRequest request) {
        boolean hasIdToken = request.idToken() != null && !request.idToken().isBlank();
        boolean hasLinkToken = request.linkToken() != null && !request.linkToken().isBlank();
        if (hasIdToken == hasLinkToken) {
            throw GoogleAuthErrors.exactlyOneToken();
        }
        if (hasIdToken) {
            GoogleIdentity google = verifyIdToken(request.idToken(), GoogleAuthErrors::invalidTokenForLink);
            if (!google.emailVerified()) {
                throw GoogleAuthErrors.emailNotVerified();
            }
            return google;
        }
        GoogleIdentity google = verifyLinkToken(request.linkToken());
        // Spec 12.1: email trong linkToken phải trùng email tài khoản (cả hai đã chuẩn hoá); lệch → 400 (hợp đồng mobile).
        if (!google.email().equals(user.getEmail())) {
            throw GoogleAuthErrors.linkTokenForAnotherAccount();
        }
        return google;
    }

    private GoogleIdentity verifyLinkToken(String linkToken) {
        try {
            return linkTokens.verify(linkToken);
        } catch (InvalidGoogleTokenException ex) {
            throw GoogleAuthErrors.invalidTokenForLink();
        }
    }

    private GoogleIdentity verifyIdToken(String idToken, Supplier<ApiException> whenInvalid) {
        try {
            return idTokenVerifier.verify(idToken);
        } catch (InvalidGoogleTokenException ex) {
            throw whenInvalid.get();
        } catch (GoogleUnavailableException ex) {
            // Chỉ ghi loại lỗi gốc, không ghi token hay message có thể chứa dữ liệu yêu cầu.
            log.warn("Không lấy được khoá công khai của Google để xác minh ID token: {}",
                    ex.getCause() == null ? "unknown" : ex.getCause().getClass().getSimpleName());
            throw GoogleAuthErrors.googleUnavailable();
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
