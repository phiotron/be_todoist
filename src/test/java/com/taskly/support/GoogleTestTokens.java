package com.taskly.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Phát ID token GIẢ theo định dạng Google (RS256, iss/aud/sub/email/email_verified/name/exp), ký bằng cặp khoá RSA
 * sinh lúc chạy test. Test KHÔNG gọi Google thật. trusted = khoá verifier trong test tin; untrusted = khoá lạ.
 */
public class GoogleTestTokens {

    public static final String WEB_CLIENT_ID = "test-web-client.apps.googleusercontent.com";
    public static final String ANDROID_CLIENT_ID = "test-android-client.apps.googleusercontent.com";
    /** Phải khớp taskly.google.client-ids trong src/test/resources/application-test.yml. */
    public static final List<String> CLIENT_IDS = List.of(WEB_CLIENT_ID, ANDROID_CLIENT_ID);

    private final Clock clock;
    private final RSAKey trustedKey = generateKey("trusted-test-key");
    private final RSAKey untrustedKey = generateKey("untrusted-test-key");

    public GoogleTestTokens(Clock clock) {
        this.clock = clock;
    }

    public RSAPublicKey trustedPublicKey() {
        try {
            return trustedKey.toRSAPublicKey();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String uniqueSubject() {
        return "google-sub-" + UUID.randomUUID();
    }

    /** ID token hợp lệ mặc định: sub + email ngẫu nhiên, email_verified = true, aud = Web client ID, sống 1 giờ. */
    public IdToken idToken() {
        return new IdToken();
    }

    public final class IdToken {

        private String subject = uniqueSubject();
        private String email = "google-" + UUID.randomUUID() + "@gmail.com";
        private Object emailVerified = Boolean.TRUE;
        private String name = "Người dùng Google";
        private String issuer = "https://accounts.google.com";
        private List<String> audience = List.of(WEB_CLIENT_ID);
        private Duration lifetime = Duration.ofHours(1);
        private boolean withExpiry = true;
        private RSAKey signingKey = trustedKey;

        private IdToken() {
        }

        public IdToken subject(String value) {
            this.subject = value;
            return this;
        }

        /** null = bỏ claim email. */
        public IdToken email(String value) {
            this.email = value;
            return this;
        }

        /** Boolean hoặc String ("true"/"false") như Google có thể trả; null = bỏ claim. */
        public IdToken emailVerified(Object value) {
            this.emailVerified = value;
            return this;
        }

        /** null = bỏ claim name. */
        public IdToken name(String value) {
            this.name = value;
            return this;
        }

        public IdToken issuer(String value) {
            this.issuer = value;
            return this;
        }

        public IdToken audience(String... values) {
            this.audience = List.of(values);
            return this;
        }

        /** exp = lúc ký + lifetime; lifetime âm = token đã hết hạn. */
        public IdToken lifetime(Duration value) {
            this.lifetime = value;
            return this;
        }

        public IdToken withoutExpiry() {
            this.withExpiry = false;
            return this;
        }

        public IdToken signedByUntrustedKey() {
            this.signingKey = untrustedKey;
            return this;
        }

        public String getSubject() {
            return subject;
        }

        public String getEmail() {
            return email;
        }

        public String sign() {
            Instant now = clock.instant();
            Instant expiresAt = now.plus(lifetime);
            // JwtClaimsSet bắt exp > iat: token đã hết hạn thì coi như được cấp 1 giờ trước exp (như token Google thật).
            Instant issuedAt = lifetime.isNegative() ? expiresAt.minus(Duration.ofHours(1)) : now;
            JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                    .issuer(issuer)
                    .subject(subject)
                    .audience(audience)
                    .issuedAt(issuedAt)
                    .claim("azp", ANDROID_CLIENT_ID);
            if (withExpiry) {
                claims.expiresAt(expiresAt);
            }
            if (email != null) {
                claims.claim("email", email);
            }
            if (emailVerified != null) {
                claims.claim("email_verified", emailVerified);
            }
            if (name != null) {
                claims.claim("name", name);
            }
            JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
                    .keyId(signingKey.getKeyID())
                    .type("JWT")
                    .build();
            NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)));
            return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
        }
    }

    private static RSAKey generateKey(String keyId) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(keyId)
                    .build();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
