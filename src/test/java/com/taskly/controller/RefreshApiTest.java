package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.IntegrationTestBase;
import com.taskly.entity.RefreshToken;
import com.taskly.repository.RefreshTokenRepository;
import com.taskly.security.OpaqueTokens;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class RefreshApiTest extends IntegrationTestBase {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    private static MockHttpServletRequestBuilder refresh(String refreshToken) {
        return post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("""
                {"refreshToken": "%s"}
                """.formatted(refreshToken));
    }

    @Test
    void refresh_returnsNewPairAndRevokesOldToken() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        String json = body(mockMvc.perform(refresh(user.refreshToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessTokenExpiresIn").value(900))
                .andExpect(jsonPath("$.user.id").value(user.id().toString()))
                .andReturn());
        String newRefresh = JsonPath.read(json, "$.refreshToken");
        String newAccess = JsonPath.read(json, "$.accessToken");

        assertThat(newRefresh).isNotEqualTo(user.refreshToken());
        Boolean oldRevoked = jdbcTemplate.queryForObject(
                "select revoked_at is not null from refresh_tokens where token_hash = ?",
                Boolean.class, OpaqueTokens.sha256Hex(user.refreshToken()));
        assertThat(oldRevoked).isTrue();

        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(newAccess)))
                .andExpect(status().isOk());
        mockMvc.perform(refresh(newRefresh)).andExpect(status().isOk());
    }

    /** Đường CẬP NHẬT của auditing (khác đường tạo): revoke() phải đổi updated_at mà không đụng created_at. */
    @Test
    void refresh_revokeUpdatesAuditTimestampsButKeepsCreatedAt() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String oldHash = OpaqueTokens.sha256Hex(user.refreshToken());
        Timestamp createdBefore = jdbcTemplate.queryForObject(
                "select created_at from refresh_tokens where token_hash = ?", Timestamp.class, oldHash);

        clock.advance(Duration.ofHours(1));
        mockMvc.perform(refresh(user.refreshToken())).andExpect(status().isOk());

        Timestamp createdAfter = jdbcTemplate.queryForObject(
                "select created_at from refresh_tokens where token_hash = ?", Timestamp.class, oldHash);
        Boolean revoked = jdbcTemplate.queryForObject(
                "select revoked_at is not null from refresh_tokens where token_hash = ?", Boolean.class, oldHash);
        Boolean updatedAfterCreated = jdbcTemplate.queryForObject(
                "select updated_at > created_at from refresh_tokens where token_hash = ?", Boolean.class, oldHash);
        Boolean gapAboutOneHour = jdbcTemplate.queryForObject(
                "select updated_at - created_at >= interval '59 minutes' from refresh_tokens where token_hash = ?",
                Boolean.class, oldHash);

        assertThat(revoked).isTrue();
        assertThat(createdAfter).isEqualTo(createdBefore);
        assertThat(updatedAfterCreated).isTrue();
        assertThat(gapAboutOneHour).isTrue();
    }

    @Test
    void refresh_reusingRotatedToken_returns401ProblemDetail() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        mockMvc.perform(refresh(user.refreshToken())).andExpect(status().isOk());

        mockMvc.perform(refresh(user.refreshToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Phiên đăng nhập không hợp lệ hoặc đã hết hạn"));
    }

    @Test
    void refresh_unknownToken_returns401() throws Exception {
        mockMvc.perform(refresh(OpaqueTokens.generate()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Phiên đăng nhập không hợp lệ hoặc đã hết hạn"));
    }

    @Test
    void refresh_after30Days_returns401() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        clock.advance(Duration.ofDays(30).plusMinutes(1));

        mockMvc.perform(refresh(user.refreshToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Phiên đăng nhập không hợp lệ hoặc đã hết hạn"));
    }

    @Test
    void refresh_before30Days_stillWorks() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        clock.advance(Duration.ofDays(29));

        mockMvc.perform(refresh(user.refreshToken())).andExpect(status().isOk());
    }

    @Test
    void refresh_ignoresStaleBearerHeader() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(refresh(user.refreshToken())
                        .header(HttpHeaders.AUTHORIZATION, bearer("expired-or-garbage")))
                .andExpect(status().isOk());
    }

    @Test
    void refresh_blankToken_returns400WithErrors() throws Exception {
        mockMvc.perform(refresh(" "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("refreshToken")));
    }

    /**
     * Khoá dòng thật sự: một transaction giữ SELECT ... FOR UPDATE của refresh token thì request refresh cùng token
     * phải CHỜ (không chạy xuyên qua), và khi transaction giữ khoá thu hồi + commit thì request chờ thấy token đã bị
     * thu hồi → 401. Bỏ @Lock trên findByTokenHash thì request thứ hai xong ngay với 200 → test đỏ.
     */
    @Test
    void refresh_waitsForRowLockHeldByAnotherTransactionThenRejectsRevokedToken() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String hash = OpaqueTokens.sha256Hex(user.refreshToken());
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                RefreshToken locked = refreshTokenRepository.findByTokenHash(hash).orElseThrow();
                lockHeld.countDown();
                try {
                    assertThat(release.await(10, TimeUnit.SECONDS)).as("tín hiệu nhả khoá").isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                locked.revoke(clock.instant());
            }));
            assertThat(lockHeld.await(10, TimeUnit.SECONDS)).as("transaction giữ khoá đã bắt đầu").isTrue();

            Future<MvcResult> waiting = executor.submit(() -> mockMvc.perform(refresh(user.refreshToken())).andReturn());
            Thread.sleep(300);
            assertThat(waiting.isDone()).as("request refresh phải đang chờ khoá dòng").isFalse();

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertThat(waiting.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(401);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void refresh_missingToken_returns400WithThieuRefreshToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("refreshToken")))
                .andExpect(jsonPath("$.errors[?(@.field == 'refreshToken')].message",
                        contains("Thiếu refresh token")));
    }
}
