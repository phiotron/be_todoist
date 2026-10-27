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
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;

/** Unit thuần. Secret dưới đây là giá trị GIẢ của application-test.yml, không phải bí mật thật. */
class TwoFactorTokensTest {

    private static final String TEST_SECRET = "test-only-jwt-secret-not-for-production-0123456789";

    private final MutableClock clock = new MutableClock();
    private final JwtProperties properties =
            new JwtProperties(TEST_SECRET, "taskly", Duration.ofMinutes(15), Duration.ofDays(30));
    private final JwtConfig jwtConfig = new JwtConfig();
    private final SecretKey key = jwtConfig.jwtSecretKey(properties);
    private final JwtEncoder encoder = jwtConfig.jwtEncoder(key);
    private final TwoFactorTokens tokens =
            new TwoFactorTokens(encoder, key, properties, clock, new UsedTwoFactorTokens(clock));

    /** Token giả có ĐÚNG iss / aud / sub / exp của token 2FA nhưng typ do test chọn. */
    private String forged(Object type) {
        Instant now = clock.instant();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer("taskly/2fa")
                .audience(List.of("taskly-2fa"))
                .subject(UUID.randomUUID().toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300));
        if (type != null) {
            claims.claim(JwtTokenIssuer.TYPE_CLAIM, type);
        }
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
    }

    @Test
    void tokenOfAnotherType_isRejected_evenWithTheTwoFactorIssuerAndAudience() {
        assertThat(tokens.verify(forged("two-factor"))).isPresent(); // đối chứng: đúng typ thì nhận
        assertThat(tokens.verify(forged("access"))).isEmpty();
        assertThat(tokens.verify(forged("google-link"))).isEmpty();
        assertThat(tokens.verify(forged(null))).isEmpty();
    }

    @Test
    void typeClaimOfANonStringJsonType_isRejectedWithoutThrowing() {
        assertThat(tokens.verify(forged(1))).isEmpty();
        assertThat(tokens.verify(forged(List.of("two-factor")))).isEmpty();
        assertThat(tokens.consume(forged(1))).isFalse();
    }

    @Test
    void consume_worksOnce_thenTheSameTokenIsRefused_untilItExpires() {
        String token = tokens.issue(UUID.randomUUID());

        assertThat(tokens.consume(token)).isTrue();
        assertThat(tokens.consume(token)).isFalse();
        assertThat(tokens.consume("not-a-jwt")).isFalse();
        assertThat(tokens.consume(tokens.issue(UUID.randomUUID()))).as("token khác vẫn dùng được").isTrue();
    }

    @Test
    void verify_refusesATokenThatWasConsumed_butDecodingAloneDoesNotBurnIt() {
        UUID userId = UUID.randomUUID();
        String token = tokens.issue(userId);

        assertThat(tokens.verify(token)).contains(userId);
        assertThat(tokens.verify(token)).as("verify không đốt token").contains(userId);
        assertThat(tokens.consume(token)).isTrue();
        assertThat(tokens.verify(token)).as("đã dùng → như token không hợp lệ").isEmpty();
    }

    @Test
    void roundTrip_carriesTheUserId() {
        UUID userId = UUID.randomUUID();

        assertThat(tokens.verify(tokens.issue(userId))).contains(userId);
    }

    @Test
    void validForFiveMinutes() {
        UUID userId = UUID.randomUUID();
        String token = tokens.issue(userId);

        clock.advance(Duration.ofMinutes(4));
        assertThat(tokens.verify(token)).contains(userId);

        clock.advance(Duration.ofMinutes(3)); // 7 phút > 5 phút + dung sai 60 giây
        assertThat(tokens.verify(token)).isEmpty();
    }

    @Test
    void tamperedOrGarbageTokens_areRejected() {
        String token = tokens.issue(UUID.randomUUID());
        String other = tokens.issue(UUID.randomUUID());
        String forged = token.substring(0, token.lastIndexOf('.')) + other.substring(other.lastIndexOf('.'));

        assertThat(tokens.verify(forged)).isEmpty();
        assertThat(tokens.verify("not-a-jwt")).isEmpty();
        assertThat(tokens.verify("")).isEmpty();
        assertThat(tokens.verify(null)).isEmpty();
    }

    @Test
    void accessToken_isNotATwoFactorToken() {
        String accessToken = new JwtTokenIssuer(encoder, properties, clock).issueAccessToken(UUID.randomUUID());

        assertThat(tokens.verify(accessToken)).isEmpty();
    }

    @Test
    void twoFactorToken_isNotAnAccessToken() {
        JwtDecoder accessDecoder = jwtConfig.jwtDecoder(key, properties, clock);
        String twoFactorToken = tokens.issue(UUID.randomUUID());

        assertThatThrownBy(() -> accessDecoder.decode(twoFactorToken)).isInstanceOf(JwtException.class);
    }
}
