package com.taskly.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.support.M13cApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * POST oauth/token và oauth/revoke: giới hạn theo IP, chung một bộ đếm (test: 10 lần / phút; mặc định 30). Mỗi test
 * dùng IP riêng.
 */
class OAuthRateLimitApiTest extends M13cApiTestBase {

    private static final int LIMIT = 10;

    private ResultActions tokenFrom(String ip) throws Exception {
        return mockMvc.perform(post("/api/v1/oauth/token")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "authorization_code")
                .param("client_id", "khong-ton-tai"));
    }

    private ResultActions revokeFrom(String ip) throws Exception {
        return mockMvc.perform(post("/api/v1/oauth/revoke")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("client_id", "khong-ton-tai")
                .param("client_secret", "sai")
                .param("token", "tkl_x"));
    }

    private static ResultActions expectRateLimited(ResultActions result) throws Exception {
        return result.andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Retry-After", org.hamcrest.Matchers.matchesPattern("[1-9][0-9]*")))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.title").value("Quá nhiều yêu cầu"));
    }

    @Test
    void token_nPlusFirstRequestInAMinute_returns429WithRetryAfter() throws Exception {
        String ip = "10.30.0.1";
        for (int i = 0; i < LIMIT; i++) {
            tokenFrom(ip).andExpect(status().is4xxClientError())
                    .andExpect(result -> org.assertj.core.api.Assertions
                            .assertThat(result.getResponse().getStatus()).isNotEqualTo(429));
        }

        expectRateLimited(tokenFrom(ip));
    }

    @Test
    void revoke_nPlusFirstRequestInAMinute_returns429WithRetryAfter() throws Exception {
        String ip = "10.30.0.2";
        for (int i = 0; i < LIMIT; i++) {
            revokeFrom(ip).andExpect(result -> org.assertj.core.api.Assertions
                    .assertThat(result.getResponse().getStatus()).isNotEqualTo(429));
        }

        expectRateLimited(revokeFrom(ip));
    }

    @Test
    void tokenAndRevokeShareOneCounter_andOtherIpsAreUnaffected() throws Exception {
        String ip = "10.30.0.3";
        for (int i = 0; i < LIMIT / 2; i++) {
            tokenFrom(ip);
            revokeFrom(ip);
        }

        expectRateLimited(tokenFrom(ip));
        expectRateLimited(revokeFrom(ip));
        tokenFrom("10.30.0.4").andExpect(result -> org.assertj.core.api.Assertions
                .assertThat(result.getResponse().getStatus()).isNotEqualTo(429));
    }

    @Test
    void counterIsClearedBetweenTestsAndWindowSlides() throws Exception {
        String ip = "10.30.0.5";
        for (int i = 0; i < LIMIT; i++) {
            tokenFrom(ip);
        }
        expectRateLimited(tokenFrom(ip));

        clock.advance(java.time.Duration.ofSeconds(61));

        tokenFrom(ip).andExpect(result -> org.assertj.core.api.Assertions
                .assertThat(result.getResponse().getStatus()).isNotEqualTo(429));
    }
}
