package com.taskly.integration.calendar;

import static com.taskly.integration.calendar.RestCalls.map;
import static com.taskly.integration.calendar.RestCalls.text;

import com.taskly.config.CalendarProperties;
import com.taskly.entity.CalendarProvider;
import com.taskly.security.ExternalServiceUnavailableException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Outlook / Microsoft 365 qua Microsoft Graph (learn.microsoft.com/graph/api/resources/calendar). OAuth 2.0 authorization
 * code (Microsoft Entra): scope offline_access Calendars.ReadWrite User.Read. Giờ đọc / ghi theo UTC (Prefer
 * outlook.timezone="UTC"). Không log token. Chưa thử với tài khoản thật (cần ứng dụng Microsoft Entra — spec 15).
 */
public class OutlookCalendarClient implements CalendarClient {

    static final String LOGIN = "https://login.microsoftonline.com/";
    static final String GRAPH = "https://graph.microsoft.com/v1.0";
    static final String SCOPES = "offline_access Calendars.ReadWrite User.Read";
    static final String TASKLY_CALENDAR_NAME = "Taskly";
    private static final String UTC_PREFER = "outlook.timezone=\"UTC\"";
    private static final DateTimeFormatter GRAPH_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final CalendarProperties.Provider config;
    private final RestClient http = RestClient.create();

    public OutlookCalendarClient(CalendarProperties properties) {
        this.config = properties.outlook();
    }

    @Override
    public CalendarProvider provider() {
        return CalendarProvider.OUTLOOK;
    }

    @Override
    public boolean configured() {
        return config.configured();
    }

    @Override
    public String authorizationUrl(String state, String redirectUri) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("client_id", config.clientId());
        query.put("response_type", "code");
        query.put("redirect_uri", redirectUri);
        query.put("response_mode", "query");
        query.put("scope", SCOPES);
        query.put("prompt", "select_account");
        query.put("state", state);
        return LOGIN + config.tenant() + "/oauth2/v2.0/authorize?" + GoogleCalendarClient.encode(query);
    }

    @Override
    public CalendarAccount exchangeCode(String code, String redirectUri) {
        requireConfigured();
        MultiValueMap<String, String> form = baseForm();
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        form.add("grant_type", "authorization_code");
        CalendarTokens tokens = tokens(postForm(form));
        Map<String, Object> me = RestCalls.call(() -> body(http.get().uri(GRAPH + "/me")
                .headers(h -> h.setBearerAuth(tokens.accessToken())).retrieve().body(Map.class)));
        String email = text(me.get("mail"));
        return new CalendarAccount(tokens, email != null ? email : text(me.get("userPrincipalName")));
    }

    @Override
    public CalendarTokens refresh(String refreshToken) {
        requireConfigured();
        if (refreshToken == null) {
            throw new CalendarAuthException();
        }
        MultiValueMap<String, String> form = baseForm();
        form.add("refresh_token", refreshToken);
        form.add("grant_type", "refresh_token");
        return tokens(postForm(form));
    }

    @Override
    public List<ExternalEvent> listEvents(String accessToken, Instant from, Instant to) {
        Map<String, Object> page = RestCalls.call(() -> body(http.get()
                .uri(GRAPH + "/me/calendarView?startDateTime={from}&endDateTime={to}&$top=250&$orderby=start/dateTime",
                        from.toString(), to.toString())
                .headers(h -> {
                    h.setBearerAuth(accessToken);
                    h.add("Prefer", UTC_PREFER);
                }).retrieve().body(Map.class)));
        return events(page).stream().filter(event -> !event.cancelled()).toList();
    }

    @Override
    public String ensureTasklyCalendar(String accessToken, String knownCalendarId) {
        if (knownCalendarId != null) {
            String existing = RestCalls.callOrMissing(() -> text(body(http.get()
                    .uri(GRAPH + "/me/calendars/{id}", knownCalendarId)
                    .headers(h -> h.setBearerAuth(accessToken)).retrieve().body(Map.class)).get("id")), null);
            if (existing != null) {
                return existing;
            }
        }
        Map<String, Object> list = RestCalls.call(() -> body(http.get().uri(GRAPH + "/me/calendars?$top=100")
                .headers(h -> h.setBearerAuth(accessToken)).retrieve().body(Map.class)));
        for (Object item : (List<?>) list.getOrDefault("value", List.of())) {
            Map<String, Object> calendar = map(item);
            if (TASKLY_CALENDAR_NAME.equals(calendar.get("name"))) {
                return text(calendar.get("id"));
            }
        }
        Map<String, Object> created = RestCalls.call(() -> body(http.post().uri(GRAPH + "/me/calendars")
                .headers(h -> h.setBearerAuth(accessToken)).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", TASKLY_CALENDAR_NAME)).retrieve().body(Map.class)));
        return text(created.get("id"));
    }

    @Override
    public String createEvent(String accessToken, String calendarId, EventDraft draft) {
        Map<String, Object> created = RestCalls.call(() -> body(http.post()
                .uri(GRAPH + "/me/calendars/{cal}/events", calendarId)
                .headers(h -> h.setBearerAuth(accessToken)).contentType(MediaType.APPLICATION_JSON)
                .body(eventBody(draft)).retrieve().body(Map.class)));
        return text(created.get("id"));
    }

    @Override
    public boolean updateEvent(String accessToken, String calendarId, String eventId, EventDraft draft) {
        return RestCalls.callOrMissing(() -> {
            http.patch().uri(GRAPH + "/me/events/{id}", eventId)
                    .headers(h -> h.setBearerAuth(accessToken)).contentType(MediaType.APPLICATION_JSON)
                    .body(eventBody(draft)).retrieve().toBodilessEntity();
            return true;
        }, false);
    }

    @Override
    public void deleteEvent(String accessToken, String calendarId, String eventId) {
        RestCalls.callOrMissing(() -> {
            http.delete().uri(GRAPH + "/me/events/{id}", eventId)
                    .headers(h -> h.setBearerAuth(accessToken)).retrieve().toBodilessEntity();
            return true;
        }, true);
    }

    @Override
    public List<ExternalEvent> changedEvents(String accessToken, String calendarId, Instant since) {
        Map<String, Object> page = RestCalls.call(() -> body(http.get()
                .uri(GRAPH + "/me/calendars/{cal}/events?$filter=lastModifiedDateTime ge {since}&$top=250",
                        calendarId, since.truncatedTo(ChronoUnit.SECONDS).toString())
                .headers(h -> {
                    h.setBearerAuth(accessToken);
                    h.add("Prefer", UTC_PREFER);
                }).retrieve().body(Map.class)));
        return events(page);
    }

    private void requireConfigured() {
        if (!configured()) {
            throw new ExternalServiceUnavailableException(new IllegalStateException("OUTLOOK_CALENDAR_CLIENT_ID"));
        }
    }

    private MultiValueMap<String, String> baseForm() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", config.clientId());
        form.add("client_secret", config.clientSecret());
        form.add("scope", SCOPES);
        return form;
    }

    private Map<String, Object> postForm(MultiValueMap<String, String> form) {
        return RestCalls.token(() -> body(http.post().uri(LOGIN + config.tenant() + "/oauth2/v2.0/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Map.class)));
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
        for (Object item : (List<?>) page.getOrDefault("value", List.of())) {
            Map<String, Object> event = map(item);
            String id = text(event.get("id"));
            if (id == null) {
                continue;
            }
            if (Boolean.TRUE.equals(event.get("isCancelled"))) {
                result.add(ExternalEvent.cancelled(id));
                continue;
            }
            Instant start = utc(map(event.get("start")).get("dateTime"));
            Instant end = utc(map(event.get("end")).get("dateTime"));
            if (start == null) {
                continue;
            }
            String title = text(event.get("subject"));
            ExternalEvent parsed = Boolean.TRUE.equals(event.get("isAllDay"))
                    ? ExternalEvent.allDay(id, title, start.atZone(ZoneOffset.UTC).toLocalDate(),
                            (end == null ? start : end).atZone(ZoneOffset.UTC).toLocalDate())
                    : ExternalEvent.timed(id, title, start, end == null ? start : end);
            result.add(parsed.withLocation(text(map(event.get("location")).get("displayName"))));
        }
        return result;
    }

    private static Instant utc(Object value) {
        return value instanceof String text && !text.isBlank()
                ? LocalDateTime.parse(text).toInstant(ZoneOffset.UTC) : null;
    }

    private static Map<String, Object> eventBody(EventDraft draft) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subject", draft.title());
        body.put("body", Map.of("contentType", "text", "content", draft.description()));
        body.put("isAllDay", draft.allDay());
        LocalDateTime start = draft.allDay() ? draft.date().atStartOfDay()
                : LocalDateTime.ofInstant(draft.start(), ZoneOffset.UTC);
        LocalDateTime end = draft.allDay() ? draft.date().plusDays(1).atStartOfDay()
                : LocalDateTime.ofInstant(draft.end(), ZoneOffset.UTC);
        body.put("start", Map.of("dateTime", start.format(GRAPH_TIME), "timeZone", "UTC"));
        body.put("end", Map.of("dateTime", end.format(GRAPH_TIME), "timeZone", "UTC"));
        return body;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(Map<?, ?> value) {
        return value == null ? Map.of() : (Map<String, Object>) value;
    }
}
