package com.taskly.security;

import com.taskly.repository.OAuthRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Bearer có hai loại: JWT của app (delegate, giữ nguyên mọi kiểm tra typ / aud) và token OAuth mờ "tkl_..." của ứng dụng
 * bên thứ ba (tra DB theo SHA-256, chỉ còn hiệu lực khi chưa thu hồi). Token OAuth được dựng thành một Jwt (typ =
 * oauth-access, sub = user, scope) để controller hiện có dùng nguyên @AuthenticationPrincipal Jwt; scope còn thành
 * quyền SCOPE_... nhờ JwtAuthenticationConverter mặc định. KHÔNG phải bean (tránh nhầm với jwtDecoder của token nội bộ).
 */
public class OAuthAwareJwtDecoder implements JwtDecoder {

    public static final String TOKEN_PREFIX = "tkl_";
    public static final String CLIENT_ID_CLAIM = "client_id";

    private final JwtDecoder delegate;
    private final OAuthRepository repository;
    private final Clock clock;

    public OAuthAwareJwtDecoder(JwtDecoder delegate, OAuthRepository repository, Clock clock) {
        this.delegate = delegate;
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        if (!token.startsWith(TOKEN_PREFIX)) {
            return delegate.decode(token);
        }
        Instant now = clock.instant();
        OAuthRepository.ActiveToken active = repository.findActiveToken(OpaqueTokens.sha256Hex(token))
                .orElseThrow(() -> new BadJwtException("oauth token invalid"));
        repository.touch(active.id(), now);
        return Jwt.withTokenValue(token)
                .header("alg", "none")
                .subject(active.userId().toString())
                .claim("typ", OAuthScopes.TOKEN_TYPE)
                .claim("scope", active.scopes())
                .claim(CLIENT_ID_CLAIM, active.clientId())
                .issuedAt(now)
                .build();
    }
}
