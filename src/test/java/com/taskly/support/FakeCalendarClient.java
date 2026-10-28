package com.taskly.support;

import com.taskly.entity.CalendarProvider;
import com.taskly.integration.calendar.CalendarAccount;
import com.taskly.integration.calendar.CalendarAuthException;
import com.taskly.integration.calendar.CalendarClient;
import com.taskly.integration.calendar.CalendarTokens;
import com.taskly.integration.calendar.EventDraft;
import com.taskly.integration.calendar.ExternalEvent;
import com.taskly.security.ExternalServiceUnavailableException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CalendarClient GIẢ trong bộ nhớ (M13b) — không gọi Google / Microsoft. Mỗi "tài khoản lịch" khoá theo email (test dùng
 * email ngẫu nhiên nên các test trong cùng context không đụng nhau). Test đăng ký code OAuth, sự kiện lịch chính, và giả
 * lập người dùng sửa / xoá sự kiện trong lịch "Taskly".
 */
public class FakeCalendarClient implements CalendarClient {

    public static final String AUTHORIZE_BASE = "https://calendar.fake/authorize";

    private final CalendarProvider provider;
    private final Clock clock;
    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final Map<String, String> codes = new ConcurrentHashMap<>();
    private final Map<String, String> accessTokens = new ConcurrentHashMap<>();
    private final Map<String, String> refreshTokens = new ConcurrentHashMap<>();
    private volatile boolean configured = true;

    public FakeCalendarClient(CalendarProvider provider, Clock clock) {
        this.provider = provider;
        this.clock = clock;
    }

    /** Một sự kiện trong lịch "Taskly" (đã đẩy từ task). */
    public record Stored(String id, EventDraft draft) {
    }

    public static final class Account {
        public final String email;
        public final List<ExternalEvent> primary = new ArrayList<>();
        public final Map<String, Stored> taskly = new ConcurrentHashMap<>();
        public final List<ExternalEvent> userEdits = new ArrayList<>();
        public volatile String tasklyCalendarId;
        public volatile boolean revoked;
        public volatile boolean unavailable;
        public volatile int refreshCalls;

        Account(String email) {
            this.email = email;
        }
    }

    public void setConfigured(boolean value) {
        this.configured = value;
    }

    /** Code OAuth đổi được lấy token của tài khoản lịch email (tạo tài khoản nếu chưa có). */
    public String registerCode(String email) {
        accounts.computeIfAbsent(email, Account::new);
        String code = "cal-code-" + UUID.randomUUID();
        codes.put(code, email);
        return code;
    }

    public Account account(String email) {
        return accounts.get(email);
    }

    @Override
    public CalendarProvider provider() {
        return provider;
    }

    @Override
    public boolean configured() {
        return configured;
    }

    @Override
    public String authorizationUrl(String state, String redirectUri) {
        return AUTHORIZE_BASE + "/" + provider.name().toLowerCase() + "?state=" + state + "&redirect_uri=" + redirectUri;
    }

    @Override
    public CalendarAccount exchangeCode(String code, String redirectUri) {
        String email = codes.remove(code);
        if (email == null) {
            throw new CalendarAuthException();
        }
        return new CalendarAccount(issue(email), email);
    }

    @Override
    public CalendarTokens refresh(String refreshToken) {
        String email = refreshTokens.get(refreshToken);
        if (email == null || accounts.get(email).revoked) {
            throw new CalendarAuthException();
        }
        accounts.get(email).refreshCalls++;
        CalendarTokens fresh = issue(email);
        return new CalendarTokens(fresh.accessToken(), null, fresh.expiresAt());
    }

    @Override
    public List<ExternalEvent> listEvents(String accessToken, Instant from, Instant to) {
        Account account = account(accessToken, true);
        return account.primary.stream().filter(event -> overlaps(event, from, to)).toList();
    }

    @Override
    public String ensureTasklyCalendar(String accessToken, String knownCalendarId) {
        Account account = account(accessToken, true);
        if (account.tasklyCalendarId == null) {
            account.tasklyCalendarId = "taskly-cal-" + UUID.randomUUID();
        }
        return account.tasklyCalendarId;
    }

    @Override
    public String createEvent(String accessToken, String calendarId, EventDraft draft) {
        Account account = account(accessToken, true);
        String id = "evt-" + UUID.randomUUID();
        account.taskly.put(id, new Stored(id, draft));
        return id;
    }

    @Override
    public boolean updateEvent(String accessToken, String calendarId, String eventId, EventDraft draft) {
        Account account = account(accessToken, true);
        if (!account.taskly.containsKey(eventId)) {
            return false;
        }
        account.taskly.put(eventId, new Stored(eventId, draft));
        return true;
    }

    @Override
    public void deleteEvent(String accessToken, String calendarId, String eventId) {
        account(accessToken, true).taskly.remove(eventId);
    }

    @Override
    public List<ExternalEvent> changedEvents(String accessToken, String calendarId, Instant since) {
        Account account = account(accessToken, true);
        List<ExternalEvent> edits = List.copyOf(account.userEdits);
        account.userEdits.clear();
        return edits;
    }

    /** Giả lập người dùng dời sự kiện trong lịch "Taskly" (ghi lại cả vào sự kiện đang lưu). */
    public void userMoves(String email, String eventId, ExternalEvent edited) {
        Account account = accounts.get(email);
        account.userEdits.add(edited);
    }

    private CalendarTokens issue(String email) {
        String access = "cal-access-" + UUID.randomUUID();
        String refresh = "cal-refresh-" + UUID.randomUUID();
        accessTokens.put(access, email);
        refreshTokens.put(refresh, email);
        return new CalendarTokens(access, refresh, clock.instant().plus(Duration.ofHours(1)));
    }

    private Account account(String accessToken, boolean checkAvailability) {
        String email = accessTokens.get(accessToken);
        if (email == null) {
            throw new CalendarAuthException();
        }
        Account account = accounts.get(email);
        if (account.revoked) {
            throw new CalendarAuthException();
        }
        if (checkAvailability && account.unavailable) {
            throw new ExternalServiceUnavailableException(new IllegalStateException("fake"));
        }
        return account;
    }

    private static boolean overlaps(ExternalEvent event, Instant from, Instant to) {
        if (event.allDay()) {
            Instant start = event.startDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            return start.isBefore(to) && !event.endDateExclusive().atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
                    .isBefore(from);
        }
        return event.start().isBefore(to) && event.end().isAfter(from);
    }
}
