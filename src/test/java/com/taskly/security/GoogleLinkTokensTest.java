package com.taskly.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import com.taskly.support.MutableClock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
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

    /** Ký bằng đúng khoá của server, đủ sub + email, chỉ cho phép chọn iss / typ / aud — để mỗi claim được thử riêng. */
    private String craft(String issuer, String type, String audience) {
        Instant now = Instant.now(clock);
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(List.of(audience))
                .subject("google-sub-crafted")
                .claim("email", "an@gmail.com")
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(10)));
        if (type != null) {
            claims.claim("typ", type);
        }
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
    }

    private static String payloadOf(String token) {
        return new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    // Spec 3.1 (12.1, "Kiểu token"): linkToken = typ google-link, aud taskly-google-link; iss riêng giữ làm lớp phòng thủ.
    @Test
    void carriesTypeAndAudienceOfSpec31() {
        String payload = payloadOf(linkTokens.issue(google()));

        assertThat(JsonPath.<String>read(payload, "$.typ")).isEqualTo("google-link");
        // Encoder ghi aud một phần tử thành chuỗi (RFC 7519 cho phép) — không phải mảng.
        assertThat(JsonPath.<String>read(payload, "$.aud")).isEqualTo("taskly-google-link");
        assertThat(JsonPath.<String>read(payload, "$.iss")).isEqualTo("taskly/google-link");
    }

    // Token mẫu hợp lệ rồi từng ca chỉ sai ĐÚNG một claim (typ sai, thiếu typ, aud của token khác, aud bản 2).
    @Test
    void typeAndAudienceAreEachRequired() {
        String issuer = properties.issuer() + "/google-link";

        assertThat(linkTokens.verify(craft(issuer, "google-link", "taskly-google-link")).subject())
                .isEqualTo("google-sub-crafted");
        assertThatThrownBy(() -> linkTokens.verify(craft(issuer, "two-factor", "taskly-google-link")))
                .isInstanceOf(InvalidGoogleTokenException.class);
        assertThatThrownBy(() -> linkTokens.verify(craft(issuer, null, "taskly-google-link")))
                .isInstanceOf(InvalidGoogleTokenException.class);
        assertThatThrownBy(() -> linkTokens.verify(craft(issuer, "google-link", "taskly-2fa")))
                .isInstanceOf(InvalidGoogleTokenException.class);
        assertThatThrownBy(() -> linkTokens.verify(craft(issuer, "google-link", "google-link")))
                .isInstanceOf(InvalidGoogleTokenException.class);
    }

    // twoFactorToken (typ two-factor, aud taskly-2fa) và access token (typ access, aud taskly-api) của spec 3.1
    // không phải linkToken — kể cả khi mượn iss của access token, của twoFactorToken hay của chính linkToken.
    @Test
    void twoFactorAndAccessTokensAreNotLinkTokens() {
        for (String issuer : List.of(properties.issuer(), properties.issuer() + "/2fa",
                properties.issuer() + "/google-link")) {
            assertThatThrownBy(() -> linkTokens.verify(craft(issuer, "two-factor", "taskly-2fa")))
                    .isInstanceOf(InvalidGoogleTokenException.class);
            assertThatThrownBy(() -> linkTokens.verify(craft(issuer, "access", "taskly-api")))
                    .isInstanceOf(InvalidGoogleTokenException.class);
        }
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
