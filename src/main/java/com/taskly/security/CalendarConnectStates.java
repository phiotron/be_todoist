package com.taskly.security;

import com.taskly.entity.CalendarProvider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * Tham số "state" của OAuth Calendar (M13b): JWT HS256 typ "calendar-connect", aud "taskly-calendar-connect", sống 10
 * phút, chứa sub = userId + provider. Callback công khai (không Bearer) biết user nào đang nối lịch và chặn giả mạo
 * (CSRF). Không dùng được làm Bearer hay linkToken (typ / aud / iss riêng).
 */
@Component
public class CalendarConnectStates {

    public static final String TYPE = "calendar-connect";
    public static final String AUDIENCE = "taskly-calendar-connect";
    public static final Duration TTL = Duration.ofMinutes(10);
    static final String PROVIDER_CLAIM = "provider";

    private final JwtEncoder jwtEncoder;
    private final NimbusJwtDecoder decoder;
    private final String issuer;
    private final Clock clock;

    public record State(UUID userId, CalendarProvider provider) {
    }

    public CalendarConnectStates(JwtEncoder jwtEncoder, SecretKey jwtSecretKey, JwtProperties properties, Clock clock) {
        this.jwtEncoder = jwtEncoder;
        this.clock = clock;
        this.issuer = properties.issuer() + "/" + TYPE;
        this.decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator();
        timestamps.setClock(clock);
        timestamps.setAllowEmptyExpiryClaim(false);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestamps,
                new JwtIssuerValidator(issuer),
                new JwtAudienceValidator(AUDIENCE),
                new JwtClaimValidator<String>("typ", TYPE::equals),
                new JwtClaimValidator<String>(JwtClaimNames.SUB, sub -> sub != null && !sub.isBlank()),
                new JwtClaimValidator<String>(PROVIDER_CLAIM, provider -> provider != null && !provider.isBlank())));
    }

    public String issue(UUID userId, CalendarProvider provider) {
        Instant now = Instant.now(clock);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(List.of(AUDIENCE))
                .claim("typ", TYPE)
                .subject(userId.toString())
                .claim(PROVIDER_CLAIM, provider.name())
                .issuedAt(now)
                .expiresAt(now.plus(TTL))
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    /** @throws InvalidExternalTokenException state sai / hết hạn / không phải state nối lịch */
    public State verify(String state) {
        if (state == null || state.isBlank()) {
            throw new InvalidExternalTokenException();
        }
        try {
            Jwt jwt = decoder.decode(state);
            return new State(UUID.fromString(jwt.getSubject()),
                    CalendarProvider.valueOf(jwt.getClaimAsString(PROVIDER_CLAIM)));
        } catch (JwtException | IllegalArgumentException ex) {
            throw new InvalidExternalTokenException();
        }
    }
}
