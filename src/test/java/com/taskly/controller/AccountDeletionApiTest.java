package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.support.TwoFactorSteps;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** DELETE me (spec 12.1): mật khẩu (+ mã 2FA nếu bật) → xoá vĩnh viễn user và mọi dữ liệu của M0 / M3. */
class AccountDeletionApiTest extends IntegrationTestBase {

    private ResultActions deleteMe(String accessToken, String json) throws Exception {
        return mockMvc.perform(delete("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private int count(String sql, UUID userId) {
        return jdbcTemplate.queryForObject(sql, Integer.class, userId);
    }

    private void assertNothingLeft(UUID userId) {
        assertThat(count("select count(*) from users where id = ?", userId)).isZero();
        assertThat(count("select count(*) from workspaces where owner_id = ?", userId)).isZero();
        assertThat(count("select count(*) from workspace_members where user_id = ?", userId)).isZero();
        assertThat(count("select count(*) from refresh_tokens where user_id = ?", userId)).isZero();
        assertThat(count("select count(*) from password_reset_tokens where user_id = ?", userId)).isZero();
        assertThat(count("select count(*) from user_totp where user_id = ?", userId)).isZero();
        assertThat(count("select count(*) from user_recovery_codes where user_id = ?", userId)).isZero();
        // M1: dữ liệu theo workspace (dự án → section / task) và theo user (yêu thích, khoá chống gửi trùng)
        assertThat(count("select count(*) from projects p join workspaces w on w.id = p.workspace_id where w.owner_id = ?",
                userId)).isZero();
        assertThat(count("select count(*) from tasks where creator_id = ?", userId)).isZero();
        assertThat(count("select count(*) from favorites where user_id = ?", userId)).isZero();
        assertThat(count("select count(*) from idempotency_keys where user_id = ?", userId)).isZero();
    }

    private int projectsOf(UUID userId) {
        return count("select count(*) from projects p join workspaces w on w.id = p.workspace_id where w.owner_id = ?",
                userId);
    }

    @Test
    void delete_correctPassword_removesUserAndAllData_andOldCredentialsStopWorking() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        mockMvc.perform(post("/api/v1/auth/password-reset/request").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + user.email() + "\"}"))
                .andExpect(status().isOk()); // tạo thêm một dòng password_reset_tokens
        assertThat(projectsOf(user.id())).isEqualTo(1); // Inbox

        deleteMe(user.accessToken(), "{\"password\": \"" + FIXTURE_PASSWORD + "\"}")
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertNothingLeft(user.id());
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + user.email() + "\", \"password\": \"" + FIXTURE_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\": \"" + user.refreshToken() + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void delete_wrongPassword_returns400_andKeepsTheAccount() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        deleteMe(user.accessToken(), "{\"password\": \"Wrong-Pass-999\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Mật khẩu hiện tại không đúng"));

        assertThat(count("select count(*) from users where id = ?", user.id())).isOne();
    }

    @Test
    void delete_overlongPassword_returns400WithFieldError_andKeepsTheAccount() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        deleteMe(user.accessToken(), "{\"password\": \"" + "a".repeat(257) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.contains("password")));

        assertThat(count("select count(*) from users where id = ?", user.id())).isOne();
    }

    @Test
    void delete_missingPassword_returns400() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        deleteMe(user.accessToken(), "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Mật khẩu hiện tại không đúng"));
    }

    @Test
    void delete_withTwoFactor_requiresAValidCode() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.Enabled enabled = TwoFactorSteps.enable(mockMvc, clock, user.accessToken());
        String code = TwoFactorSteps.nextCode(enabled.secret(), clock);

        deleteMe(user.accessToken(), "{\"password\": \"" + FIXTURE_PASSWORD + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Mã xác minh không đúng"));
        deleteMe(user.accessToken(), "{\"password\": \"" + FIXTURE_PASSWORD + "\", \"code\": \""
                        + TwoFactorSteps.wrongCode(enabled.secret(), clock) + "\"}")
                .andExpect(status().isBadRequest());
        assertThat(count("select count(*) from users where id = ?", user.id())).isOne();

        deleteMe(user.accessToken(), "{\"password\": \"" + FIXTURE_PASSWORD + "\", \"code\": \"" + code + "\"}")
                .andExpect(status().isNoContent());
        assertNothingLeft(user.id());
    }

    @Test
    void delete_withTwoFactor_acceptsARecoveryCode() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.Enabled enabled = TwoFactorSteps.enable(mockMvc, clock, user.accessToken());

        deleteMe(user.accessToken(), "{\"password\": \"" + FIXTURE_PASSWORD + "\", \"code\": \""
                        + enabled.recoveryCodes().get(5) + "\"}")
                .andExpect(status().isNoContent());
        assertNothingLeft(user.id());
    }

    @Test
    void delete_doesNotTouchOtherUsers() throws Exception {
        RegisteredUser leaving = registerUser(uniqueEmail());
        RegisteredUser staying = registerUser(uniqueEmail());
        TwoFactorSteps.enable(mockMvc, clock, staying.accessToken());

        deleteMe(leaving.accessToken(), "{\"password\": \"" + FIXTURE_PASSWORD + "\"}")
                .andExpect(status().isNoContent());

        assertThat(count("select count(*) from users where id = ?", staying.id())).isOne();
        assertThat(projectsOf(staying.id())).isEqualTo(1);
        assertThat(count("select count(*) from refresh_tokens where user_id = ?", staying.id())).isOne();
        assertThat(count("select count(*) from user_recovery_codes where user_id = ?", staying.id())).isEqualTo(10);
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(staying.accessToken())))
                .andExpect(status().isOk());
    }

    /** Khoá dòng user: hai DELETE me song song → một 204, một 401 (user không còn), không bao giờ 500. */
    @Test
    void delete_twoParallelRequests_oneSucceedsTheOtherSeesNoUser() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return deleteMe(user.accessToken(), "{\"password\": \"" + FIXTURE_PASSWORD + "\"}")
                            .andReturn().getResponse().getStatus();
                }));
            }
            ready.await();
            go.countDown();
            java.util.List<Integer> statuses = new java.util.ArrayList<>();
            for (java.util.concurrent.Future<Integer> result : results) {
                statuses.add(result.get(30, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertThat(statuses).containsExactlyInAnyOrder(204, 401);
        } finally {
            pool.shutdownNow();
        }
        assertNothingLeft(user.id());
    }

    @Test
    void sameEmail_canRegisterAgainAfterDeletion() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        deleteMe(user.accessToken(), "{\"password\": \"" + FIXTURE_PASSWORD + "\"}")
                .andExpect(status().isNoContent());

        RegisteredUser again = registerUser(user.email());

        assertThat(again.id()).isNotEqualTo(user.id());
    }

    @Test
    void delete_withoutToken_returns401() throws Exception {
        mockMvc.perform(delete("/api/v1/me").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\": \"" + FIXTURE_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
    }
}
