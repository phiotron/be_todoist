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
 * Xác minh ID token do Google cấp, theo developers.google.com/identity/gsi/web/guides/verify-google-id-token:
 * chữ ký RS256 bằng khoá JWKS của Google; iss ∈ {accounts.google.com, https://accounts.google.com};
 * aud ∈ taskly.google.client-ids; exp còn hạn (theo bean Clock, dung sai 60 giây); có sub; có email (≤ 254 ký tự).
 * Không đăng ký thành bean JwtDecoder (tránh đụng decoder access-token của Resource Server). KHÔNG log token.
 */
public class GoogleIdTokenVerifier {

    public static final Set<String> GOOGLE_ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");
    static final int MAX_EMAIL_LENGTH = 254;

    private final JwtDecoder decoder;

    public GoogleIdTokenVerifier(JwtDecoder decoder) {
        this.decoder = decoder;
    }

    /** Bản chạy thật: tải khoá từ JWKS của Google (lười, có cache theo Cache-Control của Nimbus/Spring). */
    public static GoogleIdTokenVerifier withJwkSetUri(GoogleProperties properties, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(validator(properties, clock));
        return new GoogleIdTokenVerifier(decoder);
    }

    /** Bản cho test: một khoá công khai cố định, cùng bộ validator với bản thật. */
    public static GoogleIdTokenVerifier withPublicKey(RSAPublicKey publicKey, GoogleProperties properties,
                                                      Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(validator(properties, clock));
        return new GoogleIdTokenVerifier(decoder);
    }

    static OAuth2TokenValidator<Jwt> validator(GoogleProperties properties, Clock clock) {
        JwtTimestampValidator timestamps = new JwtTimestampValidator();
        timestamps.setClock(clock);
        timestamps.setAllowEmptyExpiryClaim(false); // thiếu exp = không bao giờ hết hạn → từ chối
        Set<String> clientIds = Set.copyOf(properties.clientIds());
        return new DelegatingOAuth2TokenValidator<>(
                timestamps,
                // Spring chuyển iss thành String; "accounts.google.com" không phải URL nên không dùng JwtIssuerValidator
                new JwtClaimValidator<Object>(JwtClaimNames.ISS,
                        iss -> iss != null && GOOGLE_ISSUERS.contains(iss.toString())),
                new JwtClaimValidator<Collection<String>>(JwtClaimNames.AUD,
                        aud -> aud != null && aud.stream().anyMatch(clientIds::contains)),
                new JwtClaimValidator<String>(JwtClaimNames.SUB, sub -> sub != null && !sub.isBlank()));
    }

    /**
     * @throws InvalidGoogleTokenException token sai chữ ký / hết hạn / sai iss / sai aud / thiếu sub, email
     * @throws GoogleUnavailableException  không tải được JWKS của Google
     */
    public GoogleIdentity verify(String idToken) {
        Jwt jwt;
        try {
            jwt = decoder.decode(idToken);
        } catch (BadJwtException ex) { // gồm JwtValidationException
            throw new InvalidGoogleTokenException();
        } catch (JwtException ex) { // NimbusJwtDecoder ném JwtException (không phải Bad...) khi lỗi tải JWKS
            throw new GoogleUnavailableException(ex);
        }
        String email = EmailNormalizer.normalize(jwt.getClaimAsString("email"));
        if (email == null || email.isEmpty() || email.length() > MAX_EMAIL_LENGTH) {
            throw new InvalidGoogleTokenException();
        }
        // Google ghi email_verified là boolean, tài liệu cũ có mẫu dạng chuỗi "true" → chấp nhận cả hai.
        boolean emailVerified = "true".equalsIgnoreCase(String.valueOf(jwt.getClaims().get("email_verified")));
        return new GoogleIdentity(jwt.getSubject(), email, emailVerified, jwt.getClaimAsString("name"));
    }
}
