package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ProblemTexts;
import com.taskly.dto.auth.AuthResponse;
import com.taskly.dto.auth.LoginRequest;
import com.taskly.dto.auth.LogoutRequest;
import com.taskly.dto.auth.RefreshRequest;
import com.taskly.dto.auth.RegisterRequest;
import com.taskly.entity.RefreshToken;
import com.taskly.entity.User;
import com.taskly.entity.Workspace;
import com.taskly.mapper.UserMapper;
import com.taskly.repository.RefreshTokenRepository;
import com.taskly.security.JwtProperties;
import com.taskly.security.JwtTokenIssuer;
import com.taskly.security.OpaqueTokens;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Xác thực: đăng ký, đăng nhập, refresh, đăng xuất. Sở hữu bảng refresh_tokens.
 * Mỗi method chạy trong MỘT transaction ghi: đăng ký = user + workspace cá nhân + ADMIN + Inbox
 * + refresh token, lỗi ở bước nào thì rollback toàn bộ.
 * Họ token (M1): login/register mở họ mới, refresh kế thừa họ, logout thu hồi cả họ.
 */
@Service
@Transactional
public class AuthService {

    /** BCrypt chỉ nhận tối đa 72 byte; Spring Security ném lỗi nếu dài hơn. */
    static final int BCRYPT_MAX_BYTES = 72;
    static final String INVALID_CREDENTIALS = "Email hoặc mật khẩu không đúng";

    private final UserService userService;
    private final WorkspaceService workspaceService;
    private final ProjectService projectService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenIssuer jwtTokenIssuer;
    private final JwtProperties jwtProperties;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    /** Hash giả để vẫn chạy BCrypt khi email không tồn tại → thời gian phản hồi như nhau, không lộ email. */
    private final String dummyPasswordHash;

    public AuthService(UserService userService, WorkspaceService workspaceService, ProjectService projectService,
                       RefreshTokenRepository refreshTokenRepository, JwtTokenIssuer jwtTokenIssuer,
                       JwtProperties jwtProperties, PasswordEncoder passwordEncoder, Clock clock) {
        this.userService = userService;
        this.workspaceService = workspaceService;
        this.projectService = projectService;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtTokenIssuer = jwtTokenIssuer;
        this.jwtProperties = jwtProperties;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode("timing-equalizer-" + UUID.randomUUID());
    }

    public AuthResponse register(RegisterRequest request) {
        User user = userService.createUser(
                request.email(), passwordEncoder.encode(request.password()), request.displayName());
        Workspace personal = workspaceService.createPersonalWorkspace(user);
        projectService.createInbox(personal);
        return issueTokens(user, UUID.randomUUID());
    }

    /** Sai email hay sai mật khẩu đều trả cùng một 401. */
    public AuthResponse login(LoginRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            throw ApiException.unauthorized(INVALID_CREDENTIALS);
        }
        Optional<User> user = userService.findByEmail(request.email());
        String hash = user.map(User::getPasswordHash).orElse(dummyPasswordHash);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hash);
        if (user.isEmpty() || !passwordMatches) {
            throw ApiException.unauthorized(INVALID_CREDENTIALS);
        }
        return issueTokens(user.get(), UUID.randomUUID());
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
                .orElseThrow(() -> ApiException.unauthorized(ProblemTexts.SESSION_INVALID));
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
