package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.common.error.ProblemCodes;
import com.taskly.dto.auth.AuthResponse;
import com.taskly.dto.auth.LoginRequest;
import com.taskly.dto.auth.LogoutRequest;
import com.taskly.dto.auth.RefreshRequest;
import com.taskly.dto.auth.RegisterRequest;
import com.taskly.dto.auth.TwoFactorVerifyRequest;
import com.taskly.dto.user.ChangePasswordRequest;
import com.taskly.entity.RefreshToken;
import com.taskly.entity.User;
import com.taskly.entity.Workspace;
import com.taskly.mapper.UserMapper;
import com.taskly.repository.RefreshTokenRepository;
import com.taskly.security.JwtProperties;
import com.taskly.security.JwtTokenIssuer;
import com.taskly.security.OpaqueTokens;
import com.taskly.security.TwoFactorTokens;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Xác thực: đăng ký, đăng nhập (+ bước 2FA), refresh, đăng xuất, đổi mật khẩu. Sở hữu bảng refresh_tokens.
 * Mỗi method chạy trong MỘT transaction ghi: đăng ký = user + workspace cá nhân + ADMIN + Inbox
 * + refresh token, lỗi ở bước nào thì rollback toàn bộ.
 * Họ token (M1): login/register mở họ mới, refresh kế thừa họ, logout thu hồi cả họ.
 */
@Service
@Transactional
public class AuthService {

    /** BCrypt chỉ nhận tối đa 72 byte; Spring Security ném lỗi nếu dài hơn. */
    static final int BCRYPT_MAX_BYTES = 72;

    /** Thuộc tính ProblemDetail mang token bước 2 khi đăng nhập tài khoản bật 2FA (spec 12.1). */
    static final String TWO_FACTOR_TOKEN_PROPERTY = "twoFactorToken";

    private final UserService userService;
    private final WorkspaceService workspaceService;
    private final ProjectService projectService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenIssuer jwtTokenIssuer;
    private final JwtProperties jwtProperties;
    private final PasswordEncoder passwordEncoder;
    private final TwoFactorService twoFactorService;
    private final TwoFactorTokens twoFactorTokens;
    private final Clock clock;
    /** Hash giả để vẫn chạy BCrypt khi email không tồn tại → thời gian phản hồi như nhau, không lộ email. */
    private final String dummyPasswordHash;

    public AuthService(UserService userService, WorkspaceService workspaceService, ProjectService projectService,
                       RefreshTokenRepository refreshTokenRepository, JwtTokenIssuer jwtTokenIssuer,
                       JwtProperties jwtProperties, PasswordEncoder passwordEncoder,
                       TwoFactorService twoFactorService, TwoFactorTokens twoFactorTokens, Clock clock) {
        this.userService = userService;
        this.workspaceService = workspaceService;
        this.projectService = projectService;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtTokenIssuer = jwtTokenIssuer;
        this.jwtProperties = jwtProperties;
        this.passwordEncoder = passwordEncoder;
        this.twoFactorService = twoFactorService;
        this.twoFactorTokens = twoFactorTokens;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode("timing-equalizer-" + UUID.randomUUID());
    }

    public AuthResponse register(RegisterRequest request) {
        User user = createAccount(request.email(), passwordEncoder.encode(request.password()), request.displayName());
        return issueTokens(user, UUID.randomUUID());
    }

    /**
     * Tạo user + workspace cá nhân (ADMIN) + Inbox trong transaction hiện tại (spec 10.1). Dùng chung cho đăng ký
     * bằng mật khẩu và đăng ký bằng Google (passwordHash null). Email đã có → 409.
     */
    public User createAccount(String normalizedEmail, String passwordHash, String displayName) {
        User user = userService.createUser(normalizedEmail, passwordHash, displayName);
        Workspace personal = workspaceService.createPersonalWorkspace(user);
        projectService.createInbox(personal);
        return user;
    }

    /**
     * Sai email, sai mật khẩu, hay tài khoản chỉ có Google (chưa có mật khẩu, M5) đều trả cùng một 401 và đều chạy BCrypt
     * (trên hash giả khi không có hash thật) → không lộ trạng thái tài khoản qua thời gian. Đúng mật khẩu nhưng tài khoản
     * bật 2FA → 401 code "two-factor-required" kèm twoFactorToken (5 phút), KHÔNG phát token phiên.
     */
    public AuthResponse login(LoginRequest request) {
        if (exceedsBcryptLimit(request.password())) {
            throw ApiException.unauthorized(ErrorMessage.INVALID_CREDENTIALS);
        }
        Optional<User> user = userService.findByEmail(request.email()).filter(User::hasPassword);
        String hash = user.map(User::getPasswordHash).orElse(dummyPasswordHash);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hash);
        if (user.isEmpty() || !passwordMatches) {
            throw ApiException.unauthorized(ErrorMessage.INVALID_CREDENTIALS);
        }
        User found = user.get();
        if (twoFactorService.isEnabled(found.getId())) {
            throw ApiException.unauthorized(ErrorMessage.TWO_FACTOR_REQUIRED)
                    .withCode(ProblemCodes.TWO_FACTOR_REQUIRED)
                    .withProperty(TWO_FACTOR_TOKEN_PROPERTY, twoFactorTokens.issue(found.getId()));
        }
        return issueTokens(found, UUID.randomUUID());
    }

    /**
     * Bước 2 của đăng nhập (auth/2fa/verify): twoFactorToken hợp lệ + mã TOTP / mã khôi phục đúng (và chưa dùng) →
     * cặp token. Token sai / hết hạn / đã dùng → 401 code "two-factor-token-invalid" (app quay về Đăng nhập); mã sai →
     * 401 code "two-factor-code-invalid" (token chưa bị đốt, app cho nhập lại — phương án A, 2026-10-27).
     * Giới hạn 5 lần / 5 phút / tài khoản: Task 13a (cần RateLimiter của M1).
     */
    public AuthResponse verifyTwoFactor(TwoFactorVerifyRequest request) {
        UUID userId = twoFactorTokens.verify(request.twoFactorToken())
                .orElseThrow(AuthService::twoFactorTokenInvalid);
        // User bị xoá, hoặc 2FA bị tắt, giữa hai bước → phiên bước 2 không còn nghĩa: token-invalid (app về Đăng nhập).
        User user = userService.findById(userId).orElseThrow(AuthService::twoFactorTokenInvalid);
        switch (twoFactorService.checkCode(userId, request.code())) { // không đọc user_totp trước đó (xem checkCode)
            case NOT_ENABLED -> throw twoFactorTokenInvalid();
            case REJECTED -> throw ApiException.unauthorized(ErrorMessage.TWO_FACTOR_CODE_INVALID)
                    .withCode(ProblemCodes.TWO_FACTOR_CODE_INVALID);
            case ACCEPTED -> { }
        }
        if (!twoFactorTokens.consume(request.twoFactorToken())) { // dùng một lần (mã sai không đốt token)
            throw twoFactorTokenInvalid();
        }
        return issueTokens(user, UUID.randomUUID());
    }

    /**
     * Xoay vòng: token cũ bị thu hồi ngay, trả cặp token mới CÙNG HỌ.
     * findByTokenHash khoá dòng → hai request dùng cùng token thì chỉ một cái thành công.
     * Chạy trong transaction ghi của class (không readOnly) nên đọc được current.getUser() (LAZY).
     */
    public AuthResponse refresh(RefreshRequest request) {
        Instant now = Instant.now(clock);
        RefreshToken current = refreshTokenRepository.findByTokenHash(OpaqueTokens.sha256Hex(request.refreshToken()))
                .filter(token -> token.isActive(now))
                .orElseThrow(() -> ApiException.unauthorized(ErrorMessage.SESSION_INVALID));
        current.revoke(now);
        return issueTokens(current.getUser(), current.getFamilyId());
    }

    /**
     * Thu hồi CẢ HỌ của refresh token gửi lên nếu nó thuộc user đang đăng nhập (kể cả khi token gửi lên đã bị
     * xoay — app gửi token cũ sau khi tự refresh). Token không thuộc user → bỏ qua.
     * Luôn trả 204 (idempotent, không cho biết token có tồn tại không).
     * Access token đang cầm vẫn dùng được tới khi hết 15 phút (JWT stateless).
     * Chạy trong transaction ghi của class nên đọc được token.getUser() (LAZY).
     */
    public void logout(UUID currentUserId, LogoutRequest request) {
        refreshTokenRepository.findByTokenHash(OpaqueTokens.sha256Hex(request.refreshToken()))
                .filter(token -> token.getUser().getId().equals(currentUserId))
                .ifPresent(token -> refreshTokenRepository.revokeFamily(token.getFamilyId(), Instant.now(clock)));
    }

    /**
     * Đổi mật khẩu khi đang đăng nhập (spec mục 12.1). Sai mật khẩu hiện tại → 400 (cố ý không 401 để app không hiểu
     * nhầm là hết phiên). Đúng → lưu hash mới, thu hồi MỌI refresh token (mọi máy) và phát cặp token mới.
     */
    public AuthResponse changePassword(UUID userId, ChangePasswordRequest request) {
        User user = userService.requireUser(userId);
        if (exceedsBcryptLimit(request.currentPassword())
                || !passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw ApiException.badRequest(ErrorMessage.CURRENT_PASSWORD_INCORRECT);
        }
        user.changePasswordHash(passwordEncoder.encode(request.newPassword()));
        revokeAllRefreshTokens(userId);
        return issueTokens(user, UUID.randomUUID());
    }

    /**
     * Thu hồi MỌI refresh token chưa thu hồi của user (đổi / đặt lại mật khẩu, đăng xuất mọi thiết bị — spec 12.1).
     * Token đã thu hồi giữ nguyên revoked_at cũ. Access token đã phát vẫn sống tới khi hết hạn (tối đa 15 phút).
     */
    public void revokeAllRefreshTokens(UUID userId) {
        Instant now = Instant.now(clock);
        refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(userId).forEach(token -> token.revoke(now));
    }

    private static ApiException twoFactorTokenInvalid() {
        return ApiException.unauthorized(ErrorMessage.TWO_FACTOR_TOKEN_INVALID)
                .withCode(ProblemCodes.TWO_FACTOR_TOKEN_INVALID);
    }

    private static boolean exceedsBcryptLimit(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES;
    }

    /** Tài khoản Google vừa tạo / đăng nhập lại (M5): mở họ refresh token mới. Tài khoản mới chưa thể bật 2FA. */
    public AuthResponse issueTokens(User user) {
        return issueTokens(user, UUID.randomUUID());
    }

    /** Phát cặp token mới; DB chỉ lưu SHA-256 của refresh token, client giữ chuỗi thô. */
    private AuthResponse issueTokens(User user, UUID familyId) {
        String refreshToken = OpaqueTokens.generate();
        Instant expiresAt = Instant.now(clock).plus(jwtProperties.refreshTtl());
        refreshTokenRepository.save(
                new RefreshToken(user, OpaqueTokens.sha256Hex(refreshToken), expiresAt, familyId));
        return new AuthResponse(
                jwtTokenIssuer.issueAccessToken(user.getId()),
                refreshToken,
                jwtTokenIssuer.accessTokenTtlSeconds(),
                UserMapper.toResponse(user));
    }
}
