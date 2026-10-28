package com.taskly;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.entity.CalendarProvider;
import com.taskly.support.FakeCalendarClient;
import com.taskly.support.M13bTestConfiguration.FakeCalendarClients;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Nền cho test Calendar (M13b): client lịch GIẢ trong bộ nhớ, dùng chung context với IntegrationTestBase. */
public abstract class CalendarIntegrationTestBase extends IntegrationTestBase {

    @Autowired
    protected FakeCalendarClients calendars;

    protected FakeCalendarClient fake(CalendarProvider provider) {
        return provider == CalendarProvider.GOOGLE ? calendars.google() : calendars.outlook();
    }

    protected static String uniqueCalendarEmail() {
        return "cal-" + UUID.randomUUID() + "@example.com";
    }

    protected ResultActions postConnect(String accessToken, String provider) throws Exception {
        return authPost(accessToken, "/api/v1/me/calendar/connect", "{\"provider\": \"%s\"}".formatted(provider));
    }

    /** state trong authorizationUrl của client giả. */
    protected static String stateOf(String authorizationUrl) {
        for (String pair : URI.create(authorizationUrl).getRawQuery().split("&")) {
            if (pair.startsWith("state=")) {
                return URLDecoder.decode(pair.substring("state=".length()), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("no state");
    }

    /** Location của callback (deep link về app). */
    protected String callback(String state, String code, String error) throws Exception {
        var request = get("/api/v1/calendar/oauth/callback");
        if (state != null) {
            request.param("state", state);
        }
        if (code != null) {
            request.param("code", code);
        }
        if (error != null) {
            request.param("error", error);
        }
        return mockMvc.perform(request).andExpect(status().isSeeOther()).andReturn().getResponse()
                .getHeader(HttpHeaders.LOCATION);
    }

    /** Nối lịch bằng đúng luồng: connect → (trình duyệt) → callback. Trả deep link. */
    protected String connectCalendar(String accessToken, CalendarProvider provider, String calendarEmail)
            throws Exception {
        String url = JsonPath.read(body(postConnect(accessToken, provider.name()).andExpect(status().isOk())
                .andReturn()), "$.authorizationUrl");
        return callback(stateOf(url), fake(provider).registerCode(calendarEmail), null);
    }

    protected ResultActions patchCalendar(String accessToken, String json) throws Exception {
        return mockMvc.perform(patch("/api/v1/me/calendar").header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
