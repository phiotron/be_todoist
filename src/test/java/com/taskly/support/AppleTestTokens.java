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
 * ID token GIẢ theo định dạng Apple (RS256, iss https://appleid.apple.com, aud = Services ID, sub, email, email_verified
 * dạng chuỗi "true", is_private_email). Ký bằng khoá RSA sinh lúc chạy test — KHÔNG gọi Apple.
 */
public class AppleTestTokens {

    /** Phải khớp taskly.apple.client-ids trong application-test.yml. */
    public static final String SERVICE_ID = "test.taskly.apple.service";

    private final Clock clock;
    private final RSAKey trustedKey = generateKey("apple-trusted");
    private final RSAKey untrustedKey = generateKey("apple-untrusted");

    public AppleTestTokens(Clock clock) {
        this.clock = clock;
    }

    public RSAPublicKey trustedPublicKey() {
        try {
            return trustedKey.toRSAPublicKey();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public IdToken idToken() {
        return new IdToken();
    }

    public final class IdToken {

        private String subject = "001234." + UUID.randomUUID().toString().replace("-", "") + ".0101";
        private String email = "apple-" + UUID.randomUUID() + "@privaterelay.appleid.com";
        private Object emailVerified = "true";
        private String issuer = "https://appleid.apple.com";
        private List<String> audience = List.of(SERVICE_ID);
        private Duration lifetime = Duration.ofMinutes(10);
        private RSAKey signingKey = trustedKey;

        private IdToken() {
        }

        public IdToken subject(String value) {
            this.subject = value;
            return this;
        }

        public IdToken email(String value) {
            this.email = value;
            return this;
        }

        public IdToken emailVerified(Object value) {
            this.emailVerified = value;
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

        public IdToken lifetime(Duration value) {
            this.lifetime = value;
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
            Instant issuedAt = lifetime.isNegative() ? expiresAt.minus(Duration.ofHours(1)) : now;
            JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                    .issuer(issuer)
                    .subject(subject)
                    .audience(audience)
                    .issuedAt(issuedAt)
                    .expiresAt(expiresAt)
                    .claim("is_private_email", "true");
            if (email != null) {
                claims.claim("email", email);
            }
            if (emailVerified != null) {
                claims.claim("email_verified", emailVerified);
            }
            JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(signingKey.getKeyID()).build();
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
