package com.taskly.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

class ProblemDetailAuthenticationEntryPointTest {

    @Test
    void whenNoResolverHandlesTheException_statusIs401NotEmpty200() {
        // resolver trả null = không ai xử lý
        ProblemDetailAuthenticationEntryPoint entryPoint =
                new ProblemDetailAuthenticationEntryPoint((request, response, handler, ex) -> null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(new MockHttpServletRequest("GET", "/api/v1/me"), response,
                new BadCredentialsException("x"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
    }
}
