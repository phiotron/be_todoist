package com.taskly.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;

/** Unit thuần: decoder Bearer chỉ nhận typ=access (review C6). Secret là giá trị GIẢ của application-test.yml. */
class JwtTokenTypeTest {

    private static final String TEST_SECRET = "test-only-jwt-secret-not-for-production-0123456789";

    private final MutableClock clock = new MutableClock();
    private final JwtProperties properties =
            new JwtProperties(TEST_SECRET, "taskly", Duration.ofMinutes(15), Duration.ofDays(30));
    private final JwtConfig jwtConfig = new JwtConfig();
    private final SecretKey key = jwtConfig.jwtSecretKey(properties);
    private final JwtEncoder encoder = jwtConfig.jwtEncoder(key);
    private final JwtDecoder decoder = jwtConfig.jwtDecoder(key, properties, clock);
    private final JwtTokenIssuer issuer = new JwtTokenIssuer(encoder, properties, clock);

    /** Token đúng iss / sub / exp nhưng typ và aud do test chọn (null = bỏ claim). */
    private String token(String type, List<String> audience) {
        Instant now = clock.instant();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer("taskly")
                .subject(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900));
        if (type != null) {
            claims.claim(JwtTokenIssuer.TYPE_CLAIM, type);
        }
        if (audience != null) {
            claims.audience(audience);
        }
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
    }

    @Test
    void issuedAccessToken_carriesTypeAndAudience_andIsAccepted() {
        UUID userId = UUID.randomUUID();

        Jwt jwt = decoder.decode(issuer.issueAccessToken(userId));

        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsString(JwtTokenIssuer.TYPE_CLAIM)).isEqualTo("access");
        assertThat(jwt.getAudience()).containsExactly("taskly-api");
    }

    @Test
    void tokenWithoutType_isRejected() {
        String noType = token(null, List.of("taskly-api"));

        assertThatThrownBy(() -> decoder.decode(noType)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenOfAnotherType_isRejected_evenWithTheAccessIssuerAndAudience() {
        String twoFactor = token("two-factor", List.of("taskly-api"));
        String googleLink = token("google-link", List.of("taskly-api"));

        assertThatThrownBy(() -> decoder.decode(twoFactor)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(googleLink)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenWithAnotherAudience_isRejected_evenWithTypeAccess() {
        String otherAudience = token("access", List.of("taskly-2fa"));
        String noAudience = token("access", null);

        assertThatThrownBy(() -> decoder.decode(otherAudience)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(noAudience)).isInstanceOf(JwtException.class);
    }

    @Test
    void wellFormedAccessToken_isAccepted() {
        assertThat(decoder.decode(token("access", List.of("taskly-api"))).getSubject()).isNotBlank();
    }
}
