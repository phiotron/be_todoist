package com.taskly.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.crypto.SecretKey;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
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
 * linkToken (spec 12.1): JWT HS256 ký bằng JWT_SECRET hiện có, sống 10 phút, chứa sub + email Google; không lưu DB.
 * typ = "google-link", aud = "taskly-google-link" (spec 3.1) và iss riêng "<app.jwt.issuer>/google-link" →
 * decoder access-token từ chối linkToken, và decoder ở đây từ chối access token / twoFactorToken. JWT không mã hoá:
 * client đọc được sub + email (vốn đã có trong ID token Google họ đang cầm).
 */
@Component
public class GoogleLinkTokens {

    /** Spec 3.1 (12.1, "Kiểu token"): claim "typ" và "aud" của linkToken (token khác: access / two-factor có typ + aud riêng). */
    public static final String TYPE = "google-link";
    public static final String AUDIENCE = "taskly-google-link";
    public static final Duration TTL = Duration.ofMinutes(10);
    static final String ISSUER_SUFFIX = "/google-link";
    static final String TYPE_CLAIM = "typ";
    static final String EMAIL_CLAIM = "email";

    private final JwtEncoder jwtEncoder;
    private final JwtDecoder decoder;
    private final String issuer;
    private final Clock clock;

    public GoogleLinkTokens(JwtEncoder jwtEncoder, SecretKey jwtSecretKey, JwtProperties properties, Clock clock) {
        this.jwtEncoder = jwtEncoder;
        this.issuer = properties.issuer() + ISSUER_SUFFIX;
        this.clock = clock;
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator();
        timestamps.setClock(clock);
        timestamps.setAllowEmptyExpiryClaim(false);
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestamps,
                new JwtIssuerValidator(issuer),
                new JwtAudienceValidator(AUDIENCE),
                new JwtClaimValidator<String>(TYPE_CLAIM, TYPE::equals),
                new JwtClaimValidator<String>(JwtClaimNames.SUB, sub -> sub != null && !sub.isBlank()),
                new JwtClaimValidator<String>(EMAIL_CLAIM, email -> email != null && !email.isBlank())));
        this.decoder = nimbus;
    }

    /** Chỉ phát cho identity có email đã được Google xác minh (GoogleIdentityService bảo đảm). */
    public String issue(GoogleIdentity identity) {
        Instant now = Instant.now(clock);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(List.of(AUDIENCE))
                .claim(TYPE_CLAIM, TYPE)
                .subject(identity.subject())
                .claim(EMAIL_CLAIM, identity.email())
                .issuedAt(now)
                .expiresAt(now.plus(TTL))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** @throws InvalidGoogleTokenException sai chữ ký / hết hạn / không phải linkToken */
    public GoogleIdentity verify(String linkToken) {
        try {
            Jwt jwt = decoder.decode(linkToken);
            return new GoogleIdentity(jwt.getSubject(), jwt.getClaimAsString(EMAIL_CLAIM), true, null);
        } catch (JwtException ex) {
            throw new InvalidGoogleTokenException();
        }
    }
}
