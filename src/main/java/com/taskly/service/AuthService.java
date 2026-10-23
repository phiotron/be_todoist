package com.taskly.service;

import com.taskly.dto.auth.AuthResponse;
import com.taskly.dto.auth.RegisterRequest;
import com.taskly.entity.RefreshToken;
import com.taskly.entity.User;
import com.taskly.entity.Workspace;
import com.taskly.mapper.UserMapper;
import com.taskly.repository.RefreshTokenRepository;
import com.taskly.security.JwtProperties;
import com.taskly.security.JwtTokenIssuer;
import com.taskly.security.OpaqueTokens;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Xác thực: đăng ký, đăng nhập, refresh, đăng xuất. Sở hữu bảng refresh_tokens.
 * Mỗi method chạy trong MỘT transaction ghi: đăng ký = user + workspace cá nhân + ADMIN + Inbox
 * + refresh token, lỗi ở bước nào thì rollback toàn bộ.
 */
@Service
@Transactional
public class AuthService {

    private final UserService userService;
    private final WorkspaceService workspaceService;
    private final ProjectService projectService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenIssuer jwtTokenIssuer;
    private final JwtProperties jwtProperties;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

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
    }

    public AuthResponse register(RegisterRequest request) {
        User user = userService.createUser(
                request.email(), passwordEncoder.encode(request.password()), request.displayName());
        Workspace personal = workspaceService.createPersonalWorkspace(user);
        projectService.createInbox(personal);
        return issueTokens(user);
    }

    /** Phát cặp token mới; DB chỉ lưu SHA-256 của refresh token, client giữ chuỗi thô. */
    private AuthResponse issueTokens(User user) {
        String refreshToken = OpaqueTokens.generate();
        Instant expiresAt = Instant.now(clock).plus(jwtProperties.refreshTtl());
        refreshTokenRepository.save(new RefreshToken(user, OpaqueTokens.sha256Hex(refreshToken), expiresAt));
        return new AuthResponse(
                jwtTokenIssuer.issueAccessToken(user.getId()),
                refreshToken,
                jwtTokenIssuer.accessTokenTtlSeconds(),
                UserMapper.toResponse(user));
    }
}
