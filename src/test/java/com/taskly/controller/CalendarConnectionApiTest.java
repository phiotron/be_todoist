package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.CalendarIntegrationTestBase;
import com.taskly.entity.CalendarProvider;
import com.taskly.service.CalendarConnectionService;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Kết nối Calendar (M13b): connect → callback → status / patch / delete; token mã hoá, chỉ 1 kết nối. */
class CalendarConnectionApiTest extends CalendarIntegrationTestBase {

    @Autowired
    private CalendarConnectionService connectionService;

    @Test
    void statusIsDisconnectedUntilConnected() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        authGet(user.accessToken(), "/api/v1/me/calendar").andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(false))
                .andExpect(jsonPath("$.provider").doesNotExist());
        mockMvc.perform(get("/api/v1/me/calendar")).andExpect(status().isUnauthorized());
    }

    @Test
    void connectBuildsAuthorizationUrlThenCallbackStoresEncryptedTokens() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String email = uniqueCalendarEmail();

        String location = connectCalendar(user.accessToken(), CalendarProvider.GOOGLE, email);

        assertThat(location).isEqualTo("taskly://calendar/connected");
        authGet(user.accessToken(), "/api/v1/me/calendar")
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.provider").value("GOOGLE"))
                .andExpect(jsonPath("$.accountEmail").value(email))
                .andExpect(jsonPath("$.showEvents").value(true))
                .andExpect(jsonPath("$.syncTasks").value(false))
                .andExpect(jsonPath("$.needsReauth").value(false));
        String stored = jdbcTemplate.queryForObject("select access_token_enc from calendar_connections "
                + "where user_id = ?", String.class, user.id());
        assertThat(stored).startsWith("v1:").doesNotContain("cal-access");
        String refresh = jdbcTemplate.queryForObject("select refresh_token_enc from calendar_connections "
                + "where user_id = ?", String.class, user.id());
        assertThat(refresh).startsWith("v1:").doesNotContain("cal-refresh");
    }

    @Test
    void onlyOneProviderAndAccountPerUser() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        connectCalendar(user.accessToken(), CalendarProvider.GOOGLE, uniqueCalendarEmail());

        postConnect(user.accessToken(), "OUTLOOK").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("calendar-already-connected"));
    }

    @Test
    void secondConnectInFlightEndsWithAlreadyConnectedDeepLink() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String urlA = com.jayway.jsonpath.JsonPath.read(body(postConnect(user.accessToken(), "GOOGLE")
                .andReturn()), "$.authorizationUrl");
        String urlB = com.jayway.jsonpath.JsonPath.read(body(postConnect(user.accessToken(), "OUTLOOK")
                .andReturn()), "$.authorizationUrl");

        String first = callback(stateOf(urlA), fake(CalendarProvider.GOOGLE).registerCode(uniqueCalendarEmail()), null);
        String second = callback(stateOf(urlB), fake(CalendarProvider.OUTLOOK).registerCode(uniqueCalendarEmail()),
                null);

        assertThat(first).isEqualTo("taskly://calendar/connected");
        assertThat(second).isEqualTo("taskly://calendar/connected?error=already-connected");
    }

    @Test
    void notConfiguredProviderIs503() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        fake(CalendarProvider.OUTLOOK).setConfigured(false);
        try {
            postConnect(user.accessToken(), "OUTLOOK").andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("calendar-not-configured"));
        } finally {
            fake(CalendarProvider.OUTLOOK).setConfigured(true);
        }
        postConnect(user.accessToken(), "{}").andExpect(status().isBadRequest());
    }

    @Test
    void callbackAlwaysRedirectsToTheAppEvenOnErrors() throws Exception {
        assertThat(callback("junk", "code", null)).isEqualTo("taskly://calendar/connected?error=invalid-state");
        assertThat(callback(null, null, null)).isEqualTo("taskly://calendar/connected?error=invalid-state");

        RegisteredUser user = registerUser(uniqueEmail());
        String url = com.jayway.jsonpath.JsonPath.read(body(postConnect(user.accessToken(), "GOOGLE").andReturn()),
                "$.authorizationUrl");
        assertThat(callback(stateOf(url), null, "access_denied")).isEqualTo("taskly://calendar/connected?error=denied");
        assertThat(callback(stateOf(url), "unknown-code", null)).isEqualTo("taskly://calendar/connected?error=denied");
        authGet(user.accessToken(), "/api/v1/me/calendar").andExpect(jsonPath("$.connected").value(false));
    }

    @Test
    void stateOfAnotherTokenTypeIsRejected() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        // access token và linkToken không dùng làm state được.
        assertThat(callback(user.accessToken(), "c", null)).endsWith("error=invalid-state");
    }

    @Test
    void patchTogglesAndDeleteDisconnects() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        patchCalendar(user.accessToken(), "{\"syncTasks\": true}").andExpect(status().isNotFound());
        connectCalendar(user.accessToken(), CalendarProvider.OUTLOOK, uniqueCalendarEmail());

        patchCalendar(user.accessToken(), "{\"syncTasks\": true, \"showEvents\": false}").andExpect(status().isOk())
                .andExpect(jsonPath("$.syncTasks").value(true)).andExpect(jsonPath("$.showEvents").value(false));
        patchCalendar(user.accessToken(), "{}").andExpect(jsonPath("$.syncTasks").value(true));

        authDelete(user.accessToken(), "/api/v1/me/calendar").andExpect(status().isNoContent());
        authDelete(user.accessToken(), "/api/v1/me/calendar").andExpect(status().isNotFound());
        authGet(user.accessToken(), "/api/v1/me/calendar").andExpect(jsonPath("$.connected").value(false));
        // Nối lại được sau khi ngắt.
        assertThat(connectCalendar(user.accessToken(), CalendarProvider.GOOGLE, uniqueCalendarEmail()))
                .isEqualTo("taskly://calendar/connected");
    }

    @Test
    void expiredAccessTokenIsRefreshedAndRevokedOneNeedsReauth() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String email = uniqueCalendarEmail();
        connectCalendar(user.accessToken(), CalendarProvider.GOOGLE, email);
        UUID connectionId = jdbcTemplate.queryForObject("select id from calendar_connections where user_id = ?",
                UUID.class, user.id());
        String before = jdbcTemplate.queryForObject("select access_token_enc from calendar_connections "
                + "where id = ?", String.class, connectionId);

        connectionService.access(connectionId); // chưa hết hạn → không làm mới
        assertThat(fake(CalendarProvider.GOOGLE).account(email).refreshCalls).isZero();

        clock.advance(Duration.ofHours(2));
        connectionService.access(connectionId);
        assertThat(fake(CalendarProvider.GOOGLE).account(email).refreshCalls).isEqualTo(1);
        String after = jdbcTemplate.queryForObject("select access_token_enc from calendar_connections where id = ?",
                String.class, connectionId);
        assertThat(after).isNotEqualTo(before);
        assertThat(jdbcTemplate.queryForObject("select refresh_token_enc is not null from calendar_connections "
                + "where id = ?", Boolean.class, connectionId)).isTrue();

        fake(CalendarProvider.GOOGLE).account(email).revoked = true;
        clock.advance(Duration.ofHours(2));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> connectionService.access(connectionId))
                .isInstanceOf(com.taskly.integration.calendar.CalendarAuthException.class);
        assertThat(jdbcTemplate.queryForObject("select needs_reauth from calendar_connections where id = ?",
                Boolean.class, connectionId)).isTrue();
    }

    @Test
    void deletingTheAccountRemovesTheConnection() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        connectCalendar(user.accessToken(), CalendarProvider.GOOGLE, uniqueCalendarEmail());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/me")
                .header("Authorization", bearer(user.accessToken()))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"password\": \"%s\"}".formatted(FIXTURE_PASSWORD))).andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject("select count(*) from calendar_connections where user_id = ?",
                Integer.class, user.id())).isZero();
    }
}
