package com.taskly.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.taskly.IntegrationTestBase;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class JwtTokenIssuerTest extends IntegrationTestBase {

    @Autowired
    private JwtTokenIssuer jwtTokenIssuer;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JwtProperties jwtProperties;

    @Test
    void bindsJwtSettingsFromConfiguration() {
        assertThat(jwtProperties.issuer()).isEqualTo("taskly");
        assertThat(jwtProperties.accessTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(jwtProperties.refreshTtl()).isEqualTo(Duration.ofDays(30));
        assertThat(jwtTokenIssuer.accessTokenTtlSeconds()).isEqualTo(900);
    }

    @Test
    void issuesHs256TokenWithSubjectIssuerAnd15MinuteLifetime() {
        UUID userId = UUID.randomUUID();

        Jwt jwt = jwtDecoder.decode(jwtTokenIssuer.issueAccessToken(userId));

        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("taskly");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(jwt.getHeaders()).containsEntry("alg", "HS256");
    }

    @Test
    void acceptsTokenJustBeforeExpiry() {
        String token = jwtTokenIssuer.issueAccessToken(UUID.randomUUID());

        clock.advance(Duration.ofMinutes(14));

        assertThat(jwtDecoder.decode(token).getSubject()).isNotNull();
    }

    @Test
    void rejectsTokenWithoutExpiry() {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("taskly")
                .audience(List.of(JwtTokenIssuer.ACCESS_AUDIENCE))
                .claim(JwtTokenIssuer.TYPE_CLAIM, JwtTokenIssuer.ACCESS_TYPE)
                .subject(UUID.randomUUID().toString())
                .issuedAt(now)
                .build();
        String token = sign(claims);

        assertThatThrownBy(() -> jwtDecoder.decode(token)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void rejectsTokenWithoutSubject() {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("taskly")
                .audience(List.of(JwtTokenIssuer.ACCESS_AUDIENCE))
                .claim(JwtTokenIssuer.TYPE_CLAIM, JwtTokenIssuer.ACCESS_TYPE)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .build();
        String token = sign(claims);

        assertThatThrownBy(() -> jwtDecoder.decode(token)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void rejectsTokenAfterExpiry() {
        String token = jwtTokenIssuer.issueAccessToken(UUID.randomUUID());

        clock.advance(Duration.ofMinutes(17)); // 15 phút sống + 60 giây dung sai mặc định của validator

        assertThatThrownBy(() -> jwtDecoder.decode(token)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void rejectsTokenFromAnotherIssuer() {
        String token = encode(jwtEncoder, "someone-else");

        assertThatThrownBy(() -> jwtDecoder.decode(token)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void rejectsTokenSignedWithAnotherKey() {
        SecretKey otherKey = new SecretKeySpec(
                "another-test-only-secret-0123456789abcdef".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        String forged = encode(new NimbusJwtEncoder(new ImmutableSecret<>(otherKey)), "taskly");

        assertThatThrownBy(() -> jwtDecoder.decode(forged)).isInstanceOf(BadJwtException.class);
    }

    private String sign(JwtClaimsSet claims) {
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private String encode(JwtEncoder encoder, String issuer) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(List.of(JwtTokenIssuer.ACCESS_AUDIENCE))
                .claim(JwtTokenIssuer.TYPE_CLAIM, JwtTokenIssuer.ACCESS_TYPE)
                .subject(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
