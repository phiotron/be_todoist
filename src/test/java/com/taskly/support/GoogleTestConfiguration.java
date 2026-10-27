package com.taskly.support;

import com.taskly.security.GoogleIdTokenVerifier;
import com.taskly.security.GoogleProperties;
import java.time.Clock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Trong test: verifier @Primary tin khoá RSA của GoogleTestTokens (cùng bộ validator iss/aud/exp với bản thật),
 * nên không bao giờ gọi JWKS của Google. Import ở IntegrationTestBase → mọi test dùng chung MỘT context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class GoogleTestConfiguration {

    @Bean
    public GoogleTestTokens googleTestTokens(Clock clock) {
        return new GoogleTestTokens(clock);
    }

    @Bean
    @Primary
    public GoogleIdTokenVerifier testGoogleIdTokenVerifier(GoogleTestTokens tokens, GoogleProperties properties,
                                                           Clock clock) {
        return GoogleIdTokenVerifier.withPublicKey(tokens.trustedPublicKey(), properties, clock);
    }
}
