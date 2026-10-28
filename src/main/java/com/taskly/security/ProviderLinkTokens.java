package com.taskly.security;

import com.taskly.entity.IdentityProvider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * linkToken của Apple / Facebook (M13b) — cùng mẫu GoogleLinkTokens (M5): JWT HS256 ký bằng JWT_SECRET, sống 10 phút,
 * chứa sub + email. Mỗi nhà cung cấp typ / aud / iss riêng ("apple-link" / "taskly-apple-link" / "<issuer>/apple-link"…)
 * nên linkToken Apple không dùng được cho Facebook, Google hay làm Bearer (và ngược lại).
 */
@Component
public class ProviderLinkTokens {

    public static final Duration TTL = Duration.ofMinutes(10);
    static final String TYPE_CLAIM = "typ";
    static final String EMAIL_CLAIM = "email";

    private final JwtEncoder jwtEncoder;
    private final Clock clock;
    private final String baseIssuer;
    private final Map<IdentityProvider, JwtDecoder> decoders = new EnumMap<>(IdentityProvider.class);

    public ProviderLinkTokens(JwtEncoder jwtEncoder, SecretKey jwtSecretKey, JwtProperties properties, Clock clock) {
        this.jwtEncoder = jwtEncoder;
        this.clock = clock;
        this.baseIssuer = properties.issuer();
        for (IdentityProvider provider : List.of(IdentityProvider.APPLE, IdentityProvider.FACEBOOK)) {
            NimbusJwtDecoder nimbus = NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
            JwtTimestampValidator timestamps = new JwtTimestampValidator();
            timestamps.setClock(clock);
            timestamps.setAllowEmptyExpiryClaim(false);
            String type = type(provider);
            nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    timestamps,
                    new JwtIssuerValidator(issuer(provider)),
                    new JwtAudienceValidator(audience(provider)),
                    new JwtClaimValidator<String>(TYPE_CLAIM, type::equals),
                    new JwtClaimValidator<String>(JwtClaimNames.SUB, sub -> sub != null && !sub.isBlank()),
                    new JwtClaimValidator<String>(EMAIL_CLAIM, email -> email != null && !email.isBlank())));
            decoders.put(provider, nimbus);
        }
    }

    /** "apple-link" / "facebook-link". */
    public static String type(IdentityProvider provider) {
        return provider.name().toLowerCase(Locale.ROOT) + "-link";
    }

    /** "taskly-apple-link" / "taskly-facebook-link". */
    public static String audience(IdentityProvider provider) {
        return "taskly-" + type(provider);
    }

    private String issuer(IdentityProvider provider) {
        return baseIssuer + "/" + type(provider);
    }

    /** Chỉ phát cho danh tính có email đã xác minh (ExternalIdentityService bảo đảm). */
    public String issue(IdentityProvider provider, ExternalIdentity identity) {
        requireSupported(provider);
        Instant now = Instant.now(clock);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer(provider))
                .audience(List.of(audience(provider)))
                .claim(TYPE_CLAIM, type(provider))
                .subject(identity.subject())
                .claim(EMAIL_CLAIM, identity.email())
                .issuedAt(now)
                .expiresAt(now.plus(TTL))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** @throws InvalidExternalTokenException sai chữ ký / hết hạn / không phải linkToken của nhà cung cấp này */
    public ExternalIdentity verify(IdentityProvider provider, String linkToken) {
        JwtDecoder decoder = decoders.get(provider);
        if (decoder == null) {
            throw new InvalidExternalTokenException();
        }
        try {
            Jwt jwt = decoder.decode(linkToken);
            return new ExternalIdentity(jwt.getSubject(), jwt.getClaimAsString(EMAIL_CLAIM), true, null);
        } catch (JwtException ex) {
            throw new InvalidExternalTokenException();
        }
    }

    private void requireSupported(IdentityProvider provider) {
        if (!decoders.containsKey(provider)) {
            throw new IllegalArgumentException("linkToken không hỗ trợ " + provider);
        }
    }
}
