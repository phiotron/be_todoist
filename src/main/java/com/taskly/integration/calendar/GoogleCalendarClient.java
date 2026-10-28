package com.taskly.integration.calendar;

import static com.taskly.integration.calendar.RestCalls.map;
import static com.taskly.integration.calendar.RestCalls.text;

import com.taskly.config.CalendarProperties;
import com.taskly.entity.CalendarProvider;
import com.taskly.security.ExternalServiceUnavailableException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Google Calendar API v3 (developers.google.com/calendar/api/v3/reference). OAuth web server flow: scope "calendar" (đọc
 * lịch chính + tạo lịch "Taskly") + "openid email"; access_type=offline + prompt=consent để luôn có refresh token.
 * Không log token. Chưa thử với tài khoản thật (cần Google Cloud bật Calendar API — spec 15).
 */
public class GoogleCalendarClient implements CalendarClient {

    static final String AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    static final String USERINFO_URL = "https://openidconnect.googleapis.com/v1/userinfo";
    static final String API = "https://www.googleapis.com/calendar/v3";
    static final String SCOPES = "openid email https://www.googleapis.com/auth/calendar";
    static final String TASKLY_CALENDAR_NAME = "Taskly";

    private final CalendarProperties.Provider config;
    private final RestClient http = RestClient.create();

    public GoogleCalendarClient(CalendarProperties properties) {
        this.config = properties.google();
    }

    @Override
    public CalendarProvider provider() {
        return CalendarProvider.GOOGLE;
    }

    @Override
    public boolean configured() {
        return config.configured();
    }

    @Override
    public String authorizationUrl(String state, String redirectUri) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("client_id", config.clientId());
        query.put("redirect_uri", redirectUri);
        query.put("response_type", "code");
        query.put("scope", SCOPES);
        query.put("access_type", "offline");
        query.put("prompt", "consent");
        query.put("include_granted_scopes", "true");
        query.put("state", state);
        return AUTH_URL + "?" + encode(query);
    }

    @Override
    public CalendarAccount exchangeCode(String code, String redirectUri) {
        requireConfigured();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", config.clientId());
        form.add("client_secret", config.clientSecret());
        form.add("redirect_uri", redirectUri);
        form.add("grant_type", "authorization_code");
        CalendarTokens tokens = tokens(postForm(form));
        Map<String, Object> userInfo = RestCalls.call(() -> body(http.get().uri(USERINFO_URL)
                .headers(h -> h.setBearerAuth(tokens.accessToken())).retrieve().body(Map.class)));
        return new CalendarAccount(tokens, text(userInfo.get("email")));
    }

    @Override
    public CalendarTokens refresh(String refreshToken) {
        requireConfigured();
        if (refreshToken == null) {
            throw new CalendarAuthException();
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("refresh_token", refreshToken);
        form.add("client_id", config.clientId());
        form.add("client_secret", config.clientSecret());
        form.add("grant_type", "refresh_token");
        return tokens(postForm(form));
    }

    @Override
    public List<ExternalEvent> listEvents(String accessToken, Instant from, Instant to) {
        Map<String, Object> page = RestCalls.call(() -> body(http.get()
                .uri(API + "/calendars/primary/events?timeMin={from}&timeMax={to}&singleEvents=true"
                        + "&orderBy=startTime&maxResults=250", from.toString(), to.toString())
                .headers(h -> h.setBearerAuth(accessToken)).retrieve().body(Map.class)));
        return events(page).stream().filter(event -> !event.cancelled()).toList();
    }

    @Override
    public String ensureTasklyCalendar(String accessToken, String knownCalendarId) {
        if (knownCalendarId != null) {
            String existing = RestCalls.callOrMissing(() -> text(body(http.get()
                    .uri(API + "/calendars/{id}", knownCalendarId)
                    .headers(h -> h.setBearerAuth(accessToken)).retrieve().body(Map.class)).get("id")), null);
            if (existing != null) {
                return existing;
            }
        }
        Map<String, Object> list = RestCalls.call(() -> body(http.get()
                .uri(API + "/users/me/calendarList?minAccessRole=owner")
                .headers(h -> h.setBearerAuth(accessToken)).retrieve().body(Map.class)));
        for (Object item : (List<?>) list.getOrDefault("items", List.of())) {
            Map<String, Object> calendar = map(item);
            if (TASKLY_CALENDAR_NAME.equals(calendar.get("summary"))) {
                return text(calendar.get("id"));
            }
        }
        Map<String, Object> created = RestCalls.call(() -> body(http.post().uri(API + "/calendars")
                .headers(h -> h.setBearerAuth(accessToken)).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("summary", TASKLY_CALENDAR_NAME)).retrieve().body(Map.class)));
        return text(created.get("id"));
    }

    @Override
    public String createEvent(String accessToken, String calendarId, EventDraft draft) {
        Map<String, Object> created = RestCalls.call(() -> body(http.post()
                .uri(API + "/calendars/{cal}/events", calendarId)
                .headers(h -> h.setBearerAuth(accessToken)).contentType(MediaType.APPLICATION_JSON)
                .body(eventBody(draft)).retrieve().body(Map.class)));
        return text(created.get("id"));
    }

    @Override
    public boolean updateEvent(String accessToken, String calendarId, String eventId, EventDraft draft) {
        return RestCalls.callOrMissing(() -> {
            http.put().uri(API + "/calendars/{cal}/events/{id}", calendarId, eventId)
                    .headers(h -> h.setBearerAuth(accessToken)).contentType(MediaType.APPLICATION_JSON)
                    .body(eventBody(draft)).retrieve().toBodilessEntity();
            return true;
        }, false);
    }

    @Override
    public void deleteEvent(String accessToken, String calendarId, String eventId) {
        RestCalls.callOrMissing(() -> {
            http.delete().uri(API + "/calendars/{cal}/events/{id}", calendarId, eventId)
                    .headers(h -> h.setBearerAuth(accessToken)).retrieve().toBodilessEntity();
            return true;
        }, true);
    }

    @Override
    public List<ExternalEvent> changedEvents(String accessToken, String calendarId, Instant since) {
        Map<String, Object> page = RestCalls.call(() -> body(http.get()
                .uri(API + "/calendars/{cal}/events?updatedMin={since}&showDeleted=true&singleEvents=true"
                        + "&maxResults=250", calendarId, since.toString())
                .headers(h -> h.setBearerAuth(accessToken)).retrieve().body(Map.class)));
        return events(page);
    }

    private void requireConfigured() {
        if (!configured()) {
            throw new ExternalServiceUnavailableException(new IllegalStateException("GOOGLE_CALENDAR_CLIENT_ID"));
        }
    }

    private Map<String, Object> postForm(MultiValueMap<String, String> form) {
        return RestCalls.token(() -> body(http.post().uri(TOKEN_URL).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().body(Map.class)));
    }

    private static CalendarTokens tokens(Map<String, Object> response) {
        String access = text(response.get("access_token"));
        if (access == null) {
            throw new CalendarAuthException();
        }
        Instant expiresAt = response.get("expires_in") instanceof Number seconds
                ? Instant.now().plusSeconds(seconds.longValue()) : null;
        return new CalendarTokens(access, text(response.get("refresh_token")), expiresAt);
    }

    private static List<ExternalEvent> events(Map<String, Object> page) {
        List<ExternalEvent> result = new ArrayList<>();
        for (Object item : (List<?>) page.getOrDefault("items", List.of())) {
            Map<String, Object> event = map(item);
            String id = text(event.get("id"));
            if (id == null) {
                continue;
            }
            if ("cancelled".equals(event.get("status"))) {
                result.add(ExternalEvent.cancelled(id));
                continue;
            }
            Map<String, Object> start = map(event.get("start"));
            Map<String, Object> end = map(event.get("end"));
            String title = text(event.get("summary"));
            ExternalEvent parsed = start.get("date") != null
                    ? ExternalEvent.allDay(id, title, LocalDate.parse((String) start.get("date")),
                            LocalDate.parse((String) end.getOrDefault("date", start.get("date"))))
                    : ExternalEvent.timed(id, title, OffsetDateTime.parse((String) start.get("dateTime")).toInstant(),
                            OffsetDateTime.parse((String) end.getOrDefault("dateTime", start.get("dateTime"))).toInstant());
            result.add(parsed.withLocation(text(event.get("location"))));
        }
        return result;
    }

    private static Map<String, Object> eventBody(EventDraft draft) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("summary", draft.title());
        body.put("description", draft.description());
        if (draft.allDay()) {
            body.put("start", Map.of("date", draft.date().toString()));
            body.put("end", Map.of("date", draft.date().plusDays(1).toString()));
        } else {
            body.put("start", Map.of("dateTime", draft.start().toString(), "timeZone", "UTC"));
            body.put("end", Map.of("dateTime", draft.end().toString(), "timeZone", "UTC"));
        }
        return body;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(Map<?, ?> value) {
        return value == null ? Map.of() : (Map<String, Object>) value;
    }

    static String encode(Map<String, String> query) {
        StringBuilder out = new StringBuilder();
        query.forEach((key, value) -> {
            if (!out.isEmpty()) {
                out.append('&');
            }
            out.append(key).append('=').append(URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                    .replace("+", "%20"));
        });
        return out.toString();
    }
}
