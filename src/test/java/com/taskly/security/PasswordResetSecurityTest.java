package com.taskly.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;

/** Unit: hai đường quên mật khẩu là public — header Authorization (có thể là token hết hạn) bị bỏ qua. */
class PasswordResetSecurityTest {

    private static MockHttpServletRequest postWithBearer(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer abc");
        return request;
    }

    @Test
    void bearerTokenResolver_ignoresHeaderOnPasswordResetPathsOnly() {
        BearerTokenResolver resolver = new SecurityConfig().bearerTokenResolver();

        assertThat(resolver.resolve(postWithBearer("/api/v1/auth/password-reset/request"))).isNull();
        assertThat(resolver.resolve(postWithBearer("/api/v1/auth/password-reset/confirm"))).isNull();
        assertThat(resolver.resolve(postWithBearer("/api/v1/auth/password-reset"))).isEqualTo("abc");
        assertThat(resolver.resolve(postWithBearer("/api/v1/me/password"))).isEqualTo("abc");
    }
}
