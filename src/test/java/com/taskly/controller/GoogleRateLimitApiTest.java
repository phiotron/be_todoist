package com.taskly.controller;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.GoogleIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** auth/google giới hạn như đăng nhập: 10 lần / phút / IP, bộ đếm riêng; vượt → 429 + Retry-After (spec 5.9). */
class GoogleRateLimitApiTest extends GoogleIntegrationTestBase {

    private static MockHttpServletRequestBuilder fromIp(String ip) {
        return post(GOOGLE_SIGN_IN).with(remoteAddress(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content(idTokenJson("not-a-jwt"));
    }

    @Test
    void eleventhGoogleSignInPerMinuteFromSameIp_returns429WithRetryAfter() throws Exception {
        String ip = "10.30.0.1";
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(fromIp(ip)).andExpect(status().isUnauthorized());
        }

        mockMvc.perform(fromIp(ip))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, matchesPattern("[1-9][0-9]*")))
                .andExpect(jsonPath("$.detail").value("Bạn thao tác quá nhanh, vui lòng thử lại sau"));
    }

    @Test
    void googleHasItsOwnCounter_loginFromSameIpStillAllowed() throws Exception {
        String ip = "10.30.0.2";
        for (int i = 0; i < 11; i++) {
            mockMvc.perform(fromIp(ip));
        }

        mockMvc.perform(post("/api/v1/auth/login").with(remoteAddress(ip))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "sai-mat-khau-123"}
                                """.formatted(uniqueEmail())))
                .andExpect(status().isUnauthorized());
    }
}
