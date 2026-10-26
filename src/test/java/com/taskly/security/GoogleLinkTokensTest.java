package com.taskly.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.support.MutableClock;
import java.time.Duration;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtException;

/** Unit test thuần. Secret dưới đây là giá trị GIẢ của application-test.yml, không phải bí mật thật. */
class GoogleLinkTokensTest {

    private static final String TEST_SECRET = "test-only-jwt-secret-not-for-production-0123456789";

    private final MutableClock clock = new MutableClock();
    private final JwtProperties properties =
            new JwtProperties(TEST_SECRET, "taskly", Duration.ofMinutes(15), Duration.ofDays(30));
    private final JwtConfig jwtConfig = new JwtConfig();
    private final SecretKey key = jwtConfig.jwtSecretKey(properties);
    private final JwtEncoder encoder = jwtConfig.jwtEncoder(key);
    private final GoogleLinkTokens linkTokens = new GoogleLinkTokens(encoder, key, properties, clock);

    private static GoogleIdentity google() {
        return new GoogleIdentity("google-sub-" + UUID.randomUUID(), "an@gmail.com", true, "An");
    }

    @Test
    void roundTripCarriesSubjectAndEmailOnly() {
        GoogleIdentity original = google();

        GoogleIdentity carried = linkTokens.verify(linkTokens.issue(original));

        assertThat(carried.subject()).isEqualTo(original.subject());
        assertThat(carried.email()).isEqualTo("an@gmail.com");
        assertThat(carried.emailVerified()).isTrue();
        assertThat(carried.name()).isNull();
    }

    @Test
    void validForTenMinutes() {
        String token = linkTokens.issue(google());

        clock.advance(Duration.ofMinutes(9));
        assertThat(linkTokens.verify(token)).isNotNull();

        clock.advance(Duration.ofMinutes(3)); // 12 phút > 10 phút + dung sai 60 giây
        assertThatThrownBy(() -> linkTokens.verify(token)).isInstanceOf(InvalidGoogleTokenException.class);
    }

    @Test
    void tamperedSignatureIsRejected() {
        String token = linkTokens.issue(google());
        String other = linkTokens.issue(google());
        String forged = token.substring(0, token.lastIndexOf('.')) + other.substring(other.lastIndexOf('.'));

        assertThatThrownBy(() -> linkTokens.verify(forged)).isInstanceOf(InvalidGoogleTokenException.class);
        assertThatThrownBy(() -> linkTokens.verify("not-a-jwt")).isInstanceOf(InvalidGoogleTokenException.class);
    }

    @Test
    void accessTokenIsNotALinkToken() {
        String accessToken = new JwtTokenIssuer(encoder, properties, clock).issueAccessToken(UUID.randomUUID());

        assertThatThrownBy(() -> linkTokens.verify(accessToken)).isInstanceOf(InvalidGoogleTokenException.class);
    }

    @Test
    void linkTokenIsNotAnAccessToken() {
        JwtDecoder accessDecoder = jwtConfig.jwtDecoder(key, properties, clock);
        String linkToken = linkTokens.issue(google());

        assertThatThrownBy(() -> accessDecoder.decode(linkToken)).isInstanceOf(JwtException.class);
    }
}
