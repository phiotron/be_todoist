package com.taskly.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Đăng nhập / đăng ký: 10 lần / phút / IP; refresh: 30 lần / phút / IP (mục 5.9).
 * Mỗi test dùng IP riêng để không ảnh hưởng nhau.
 */
class RateLimitApiTest extends IntegrationTestBase {

    private ResultActions loginFrom(String ip, String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "sai-mat-khau-123"}
                        """.formatted(email)));
    }

    private ResultActions refreshFrom(String ip, String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"refreshToken": "%s"}
                        """.formatted(refreshToken)));
    }

    private ResultActions registerFrom(String ip) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/register")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s", "displayName": "Thử"}
                        """.formatted(uniqueEmail(), FIXTURE_PASSWORD)));
    }

    @Test
    void eleventhLoginInAMinute_returns429WithRetryAfter() throws Exception {
        String ip = "10.20.0.1";
        for (int i = 0; i < 10; i++) {
            loginFrom(ip, uniqueEmail()).andExpect(status().isUnauthorized());
        }

        loginFrom(ip, uniqueEmail())
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Retry-After", org.hamcrest.Matchers.matchesPattern("[1-9][0-9]*")))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.title").value("Quá nhiều yêu cầu"))
                .andExpect(jsonPath("$.detail").value("Bạn thao tác quá nhanh, vui lòng thử lại sau"));
    }

    @Test
    void otherIp_isNotLimited_andWindowSlides() throws Exception {
        String ip = "10.20.0.2";
        for (int i = 0; i < 10; i++) {
            loginFrom(ip, uniqueEmail()).andExpect(status().isUnauthorized());
        }
        loginFrom("10.20.0.3", uniqueEmail()).andExpect(status().isUnauthorized());

        clock.advance(Duration.ofSeconds(61));
        loginFrom(ip, uniqueEmail()).andExpect(status().isUnauthorized());
    }

    @Test
    void registerHasItsOwnLimit() throws Exception {
        String ip = "10.20.0.4";
        for (int i = 0; i < 10; i++) {
            registerFrom(ip).andExpect(status().isCreated());
        }
        registerFrom(ip).andExpect(status().isTooManyRequests());

        loginFrom(ip, uniqueEmail()).andExpect(status().isUnauthorized()); // bộ đếm login riêng
    }

    @Test
    void refreshAllows30PerMinutePerIp_then429WithRetryAfter() throws Exception {
        String ip = "10.20.0.5";
        for (int i = 0; i < 30; i++) {
            refreshFrom(ip, "token-khong-ton-tai-" + i).andExpect(status().isUnauthorized());
        }

        refreshFrom(ip, "token-khong-ton-tai-31")
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Retry-After", org.hamcrest.Matchers.matchesPattern("[1-9][0-9]*")))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.title").value("Quá nhiều yêu cầu"));

        refreshFrom("10.20.0.6", "token-khong-ton-tai").andExpect(status().isUnauthorized()); // IP khác
        loginFrom(ip, uniqueEmail()).andExpect(status().isUnauthorized()); // bộ đếm login riêng

        clock.advance(Duration.ofSeconds(61));
        refreshFrom(ip, "token-khong-ton-tai").andExpect(status().isUnauthorized());
    }

    @Test
    void refreshLimitIsIndependentFromLoginLimit() throws Exception {
        String ip = "10.20.0.7";
        for (int i = 0; i < 10; i++) {
            loginFrom(ip, uniqueEmail()).andExpect(status().isUnauthorized());
        }
        loginFrom(ip, uniqueEmail()).andExpect(status().isTooManyRequests());

        refreshFrom(ip, "token-khong-ton-tai").andExpect(status().isUnauthorized());
    }
}
