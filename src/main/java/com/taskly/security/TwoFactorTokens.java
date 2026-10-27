package com.taskly.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * twoFactorToken (spec 12.1, review C6): JWT HS256 ký bằng JWT_SECRET hiện có, sống 5 phút, sub = user id; không lưu DB.
 * iss = "<app.jwt.issuer>/2fa", aud = "taskly-2fa", typ = "two-factor", jti riêng → decoder access-token (typ = access) từ chối
 * token này, decoder ở đây (kiểm typ + aud + iss) từ chối access token. Dùng MỘT lần: consume() đánh dấu jti.
 * Cùng mẫu GoogleLinkTokens (plan M5, typ = google-link). Token không mã hoá — chỉ chứa user id.
 */
@Component
public class TwoFactorTokens {

    public static final String AUDIENCE = "taskly-2fa";
    public static final String TYPE = "two-factor";
    public static final Duration TTL = Duration.ofMinutes(5);
    static final String ISSUER_SUFFIX = "/2fa";

    private final JwtEncoder jwtEncoder;
    private final JwtDecoder decoder;
    private final String issuer;
    private final Clock clock;
    private final UsedTwoFactorTokens usedTokens;

    public TwoFactorTokens(JwtEncoder jwtEncoder, SecretKey jwtSecretKey, JwtProperties properties, Clock clock,
                           UsedTwoFactorTokens usedTokens) {
        this.jwtEncoder = jwtEncoder;
        this.issuer = properties.issuer() + ISSUER_SUFFIX;
        this.clock = clock;
        this.usedTokens = usedTokens;
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator();
        timestamps.setClock(clock);
        timestamps.setAllowEmptyExpiryClaim(false);
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestamps,
                new JwtIssuerValidator(issuer),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(AUDIENCE)),
                new JwtClaimValidator<Object>(JwtTokenIssuer.TYPE_CLAIM, TYPE::equals), // Object: typ lạ kiểu số / mảng → từ chối, không ClassCastException
                new JwtClaimValidator<String>(JwtClaimNames.SUB, sub -> sub != null && !sub.isBlank())));
        this.decoder = nimbus;
    }

    public String issue(UUID userId) {
        Instant now = Instant.now(clock);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(List.of(AUDIENCE))
                .claim(JwtTokenIssuer.TYPE_CLAIM, TYPE)
                .id(UUID.randomUUID().toString())
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(TTL))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /**
     * User id trong token; sai chữ ký / hết hạn / không phải token 2FA / rỗng / ĐÃ DÙNG (consume) → rỗng (AuthService trả
     * 401 two-factor-token-invalid). verify() không đốt token — chỉ consume() mới đánh dấu đã dùng.
     */
    public Optional<UUID> verify(String twoFactorToken) {
        if (twoFactorToken == null || twoFactorToken.isBlank()) {
            return Optional.empty();
        }
        try {
            Jwt jwt = decoder.decode(twoFactorToken);
            if (jwt.getId() == null || usedTokens.isUsed(jwt.getId())) {
                return Optional.empty();
            }
            return Optional.of(UUID.fromString(jwt.getSubject()));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Đánh dấu token đã dùng (gọi SAU khi mã 2FA đúng). true = lần đầu; false = token sai / hết hạn / đã dùng rồi.
     */
    public boolean consume(String twoFactorToken) {
        if (twoFactorToken == null || twoFactorToken.isBlank()) {
            return false;
        }
        try {
            Jwt jwt = decoder.decode(twoFactorToken);
            return jwt.getId() != null && usedTokens.markUsed(jwt.getId(), jwt.getExpiresAt());
        } catch (JwtException e) {
            return false;
        }
    }
}
