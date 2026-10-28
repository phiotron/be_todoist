package com.taskly.security;

import com.taskly.common.EmailNormalizer;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.Collection;
import java.util.Set;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Xác minh ID token Apple theo developer.apple.com "Verifying a user": chữ ký RS256 bằng JWKS của Apple;
 * iss = https://appleid.apple.com; aud ∈ taskly.apple.client-ids; exp còn hạn (bean Clock); có sub; có email (≤ 254).
 * Email "ẩn" của Apple là địa chỉ relay (…@privaterelay.appleid.com) — nhận như email thường. Cùng mẫu GoogleIdTokenVerifier
 * (M5): không đăng ký thành bean JwtDecoder, KHÔNG log token.
 */
public class NimbusAppleIdTokenVerifier implements AppleIdTokenVerifier {

    static final int MAX_EMAIL_LENGTH = 254;

    private final JwtDecoder decoder;

    public NimbusAppleIdTokenVerifier(JwtDecoder decoder) {
        this.decoder = decoder;
    }

    /** Bản chạy thật: tải khoá từ JWKS của Apple (lười, có cache). */
    public static NimbusAppleIdTokenVerifier withJwkSetUri(AppleProperties properties, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(validator(properties, clock));
        return new NimbusAppleIdTokenVerifier(decoder);
    }

    /** Bản cho test: một khoá công khai cố định, cùng bộ validator với bản thật. */
    public static NimbusAppleIdTokenVerifier withPublicKey(RSAPublicKey publicKey, AppleProperties properties,
                                                           Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(validator(properties, clock));
        return new NimbusAppleIdTokenVerifier(decoder);
    }

    static OAuth2TokenValidator<Jwt> validator(AppleProperties properties, Clock clock) {
        JwtTimestampValidator timestamps = new JwtTimestampValidator();
        timestamps.setClock(clock);
        timestamps.setAllowEmptyExpiryClaim(false);
        Set<String> clientIds = Set.copyOf(properties.clientIds());
        return new DelegatingOAuth2TokenValidator<>(
                timestamps,
                new JwtClaimValidator<Object>(JwtClaimNames.ISS,
                        iss -> iss != null && AppleProperties.APPLE_ISSUER.equals(iss.toString())),
                new JwtClaimValidator<Collection<String>>(JwtClaimNames.AUD,
                        aud -> aud != null && aud.stream().anyMatch(clientIds::contains)),
                new JwtClaimValidator<String>(JwtClaimNames.SUB, sub -> sub != null && !sub.isBlank()));
    }

    @Override
    public ExternalIdentity verify(String idToken) {
        Jwt jwt;
        try {
            jwt = decoder.decode(idToken);
        } catch (BadJwtException ex) {
            throw new InvalidExternalTokenException();
        } catch (JwtException ex) { // lỗi tải JWKS
            throw new ExternalServiceUnavailableException(ex);
        }
        String email = EmailNormalizer.normalize(jwt.getClaimAsString("email"));
        if (email == null || email.isEmpty() || email.length() > MAX_EMAIL_LENGTH) {
            throw new InvalidExternalTokenException();
        }
        // Apple ghi email_verified là chuỗi "true" hoặc boolean; địa chỉ relay luôn được Apple xác minh.
        boolean emailVerified = "true".equalsIgnoreCase(String.valueOf(jwt.getClaims().get("email_verified")));
        return new ExternalIdentity(jwt.getSubject(), email, emailVerified, null);
    }
}
