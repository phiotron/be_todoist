package com.taskly.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Cấu hình taskly.google.* được bind; bean verifier tạo được mà KHÔNG gọi mạng (JWKS tải lười khi xác minh). */
class GoogleAuthConfigTest extends IntegrationTestBase {

    @Autowired
    private GoogleProperties properties;

    @Autowired
    private GoogleIdTokenVerifier verifier;

    @Test
    void bindsClientIdsAndDefaultsJwkSetUriToGoogle() {
        assertThat(properties.clientIds()).containsExactly(
                "test-web-client.apps.googleusercontent.com", "test-android-client.apps.googleusercontent.com");
        assertThat(properties.jwkSetUri()).isEqualTo("https://www.googleapis.com/oauth2/v3/certs");
        assertThat(verifier).isNotNull();
    }
}
