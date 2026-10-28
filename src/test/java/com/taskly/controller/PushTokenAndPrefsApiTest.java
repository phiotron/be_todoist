package com.taskly.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.support.M13cApiTestBase;
import java.time.Duration;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/** me/push-tokens (F6) và me/notification-prefs (F7). */
class PushTokenAndPrefsApiTest extends M13cApiTestBase {

    private static final String TOKENS = "/api/v1/me/push-tokens";
    private static final String PREFS = "/api/v1/me/notification-prefs";

    private static String register(String token) {
        return "{\"token\": \"%s\", \"platform\": \"ANDROID\"}".formatted(token);
    }

    private int tokenCount(java.util.UUID userId) {
        return jdbcTemplate.queryForObject("select count(*) from push_tokens where user_id = ?", Integer.class, userId);
    }

    @Test
    void register_isIdempotent_andStoresHash() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String token = "fcm-" + java.util.UUID.randomUUID();
        authPost(user.accessToken(), TOKENS, register(token)).andExpect(status().isNoContent());
        authPost(user.accessToken(), TOKENS, register(token)).andExpect(status().isNoContent());
        Assertions.assertThat(tokenCount(user.id())).isEqualTo(1);
        Assertions.assertThat(jdbcTemplate.queryForObject("select token_hash from push_tokens where user_id = ?",
                String.class, user.id())).hasSize(64).isNotEqualTo(token);
    }

    @Test
    void register_validatesInput() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        authPost(user.accessToken(), TOKENS, "{\"token\": \"\", \"platform\": \"ANDROID\"}").andExpect(status().isBadRequest());
        authPost(user.accessToken(), TOKENS, "{\"token\": \"   \", \"platform\": \"ANDROID\"}").andExpect(status().isBadRequest());
        authPost(user.accessToken(), TOKENS, "{\"token\": \"%s\", \"platform\": \"ANDROID\"}".formatted("x".repeat(4097)))
                .andExpect(status().isBadRequest());
        authPost(user.accessToken(), TOKENS, "{\"token\": \"t\", \"platform\": \"IOS\"}").andExpect(status().isBadRequest());
        authPost(user.accessToken(), TOKENS, "{\"token\": \"t\"}").andExpect(status().isBadRequest());
        authPost(user.accessToken(), TOKENS, register("x".repeat(4096))).andExpect(status().isNoContent());
    }

    @Test
    void aTokenMovesToTheLatestAccount() throws Exception {
        RegisteredUser first = registerUser(uniqueEmail());
        RegisteredUser second = registerUser(uniqueEmail());
        String token = "fcm-" + java.util.UUID.randomUUID();
        authPost(first.accessToken(), TOKENS, register(token)).andExpect(status().isNoContent());
        authPost(second.accessToken(), TOKENS, register(token)).andExpect(status().isNoContent());
        Assertions.assertThat(tokenCount(first.id())).isZero();
        Assertions.assertThat(tokenCount(second.id())).isEqualTo(1);
    }

    @Test
    void unregister_removesOnlyOwnTokens_andIsIdempotent() throws Exception {
        RegisteredUser owner = registerUser(uniqueEmail());
        RegisteredUser other = registerUser(uniqueEmail());
        String token = "fcm-" + java.util.UUID.randomUUID();
        authPost(owner.accessToken(), TOKENS, register(token)).andExpect(status().isNoContent());

        authPost(other.accessToken(), TOKENS + "/unregister", "{\"token\": \"%s\"}".formatted(token))
                .andExpect(status().isNoContent());
        Assertions.assertThat(tokenCount(owner.id())).isEqualTo(1);
        authPost(owner.accessToken(), TOKENS + "/unregister", "{\"token\": \"%s\"}".formatted(token))
                .andExpect(status().isNoContent());
        authPost(owner.accessToken(), TOKENS + "/unregister", "{\"token\": \"%s\"}".formatted(token))
                .andExpect(status().isNoContent());
        Assertions.assertThat(tokenCount(owner.id())).isZero();
        authPost(owner.accessToken(), TOKENS + "/unregister", "{\"token\": \"\"}").andExpect(status().isBadRequest());
    }

    @Test
    void keepsAtMostTenDevicesPerAccount_dropsTheOldest() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        for (int i = 0; i < 11; i++) {
            authPost(user.accessToken(), TOKENS, register("device-" + i)).andExpect(status().isNoContent());
            clock.advance(Duration.ofSeconds(1));
        }
        Assertions.assertThat(tokenCount(user.id())).isEqualTo(10);
        Assertions.assertThat(jdbcTemplate.queryForObject("select count(*) from push_tokens where token = 'device-0'",
                Integer.class)).isZero();
    }

    @Test
    void test_sendsToEveryDevice_removesInvalidOnes_andReportsCount() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        authPost(user.accessToken(), TOKENS + "/test").andExpect(status().isAccepted()).andExpect(jsonPath("$.devices").value(0));

        String good = "good-" + java.util.UUID.randomUUID();
        String stale = "stale-" + java.util.UUID.randomUUID();
        authPost(user.accessToken(), TOKENS, register(good)).andExpect(status().isNoContent());
        authPost(user.accessToken(), TOKENS, register(stale)).andExpect(status().isNoContent());
        pushSender.invalidate(stale);

        authPost(user.accessToken(), TOKENS + "/test").andExpect(status().isAccepted()).andExpect(jsonPath("$.devices").value(2));
        Assertions.assertThat(pushSender.sentTo(good)).hasSize(1);
        Assertions.assertThat(pushSender.sentTo(good).get(0).body()).contains("push từ máy chủ");
        Assertions.assertThat(pushSender.sentTo(stale)).isEmpty();
        Assertions.assertThat(tokenCount(user.id())).isEqualTo(1);
    }

    @Test
    void test_survivesATransientPushFailure() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        authPost(user.accessToken(), TOKENS, register("t-" + java.util.UUID.randomUUID())).andExpect(status().isNoContent());
        pushSender.setFailing(true);
        authPost(user.accessToken(), TOKENS + "/test").andExpect(status().isAccepted()).andExpect(jsonPath("$.devices").value(1));
        Assertions.assertThat(tokenCount(user.id())).isEqualTo(1); // lỗi tạm thời không xoá token
    }

    @Test
    void pushEndpointsRequireAnAppSession() throws Exception {
        mockMvc.perform(get(PREFS)).andExpect(status().isUnauthorized());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(TOKENS)
                .contentType("application/json").content(register("t"))).andExpect(status().isUnauthorized());
    }

    // ---- notification-prefs ----

    @Test
    void prefs_haveDefaults_andPatchIsPartial() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        authGet(user.accessToken(), PREFS).andExpect(status().isOk())
                .andExpect(jsonPath("$.reminderPush").value(false))
                .andExpect(jsonPath("$.reminderEmail").value(false))
                .andExpect(jsonPath("$.morningOverview").value(false))
                .andExpect(jsonPath("$.eveningReview").value(false))
                .andExpect(jsonPath("$.subscribedEmails").value(true));

        authPatch(user.accessToken(), PREFS, "{\"morningOverview\": true}").andExpect(status().isOk())
                .andExpect(jsonPath("$.morningOverview").value(true))
                .andExpect(jsonPath("$.eveningReview").value(false))
                .andExpect(jsonPath("$.subscribedEmails").value(true));
        authPatch(user.accessToken(), PREFS, "{\"subscribedEmails\": false, \"eveningReview\": true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.morningOverview").value(true))
                .andExpect(jsonPath("$.eveningReview").value(true))
                .andExpect(jsonPath("$.subscribedEmails").value(false));
        authGet(user.accessToken(), PREFS).andExpect(jsonPath("$.morningOverview").value(true))
                .andExpect(jsonPath("$.subscribedEmails").value(false));
    }

    @Test
    void prefs_nullOrEmptyPatchChangesNothing_andUsersAreIsolated() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        RegisteredUser other = registerUser(uniqueEmail());
        authPatch(user.accessToken(), PREFS, "{\"reminderEmail\": true}").andExpect(status().isOk());
        authPatch(user.accessToken(), PREFS, "{\"reminderEmail\": null}").andExpect(status().isOk())
                .andExpect(jsonPath("$.reminderEmail").value(true));
        authPatch(user.accessToken(), PREFS, "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.reminderEmail").value(true));
        authGet(other.accessToken(), PREFS).andExpect(jsonPath("$.reminderEmail").value(false));
        authPatch(user.accessToken(), PREFS, "{\"reminderEmail\": \"yes\"}").andExpect(status().isBadRequest());
    }

    @Test
    void prefs_areNotOpenToOAuthTokens() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String oauth = oauthToken(user.accessToken(), createApp(user.accessToken()), "data:delete,data:read_write");
        authGet(oauth, PREFS).andExpect(status().isForbidden());
        authGet(oauth, "/api/v1/me/authorized-apps").andExpect(status().isForbidden());
    }
}
