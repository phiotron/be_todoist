package com.taskly.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;

class SecurityErrorResponseTest extends IntegrationTestBase {

    @Autowired
    private JwtTokenIssuer jwtTokenIssuer;

    @Test
    void missingToken_returns401ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Chưa xác thực"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").value("Phiên đăng nhập không hợp lệ hoặc đã hết hạn"))
                .andExpect(jsonPath("$.instance").value("/api/v1/me"));
    }

    @Test
    void garbageToken_returns401ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer("not-a-jwt")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void expiredToken_returns401ProblemDetail() throws Exception {
        String token = jwtTokenIssuer.issueAccessToken(UUID.randomUUID());
        clock.advance(Duration.ofMinutes(17));

        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void unknownPathWithValidToken_returns404ProblemDetail() throws Exception {
        String token = jwtTokenIssuer.issueAccessToken(UUID.randomUUID());

        mockMvc.perform(get("/api/v1/khong-ton-tai").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Không tìm thấy"))
                .andExpect(jsonPath("$.detail").value("Không tìm thấy đường dẫn yêu cầu"));
    }

    @Test
    void bearerTokenResolver_ignoresHeaderOnlyOnExactPublicAuthPaths() {
        BearerTokenResolver resolver = new SecurityConfig().bearerTokenResolver();

        assertThat(resolver.resolve(postTo("/api/v1/auth/register"))).isNull();
        assertThat(resolver.resolve(postTo("/api/v1/auth/login"))).isNull();
        assertThat(resolver.resolve(postTo("/api/v1/auth/refresh"))).isNull();
        assertThat(resolver.resolve(postTo("/api/v1/auth/google"))).isNull();

        assertThat(resolver.resolve(postTo("/api/v1/auth/logout"))).isEqualTo("abc");
        assertThat(resolver.resolve(postTo("/api/v1/me"))).isEqualTo("abc");
        assertThat(resolver.resolve(postTo("/api/v1/auth/login/"))).isEqualTo("abc");
        assertThat(resolver.resolve(postTo("/API/v1/auth/login"))).isEqualTo("abc");
    }

    @Test
    void logoutWithoutToken_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void publicAuthPath_onlyPostIsPublic_andPathVariantsAreNot() throws Exception {
        mockMvc.perform(get("/api/v1/auth/login"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/login/"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/API/v1/auth/login"))
                .andExpect(status().isUnauthorized());
    }

    private static MockHttpServletRequest postTo(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.addHeader(HttpHeaders.AUTHORIZATION, bearer("abc"));
        return request;
    }
}
