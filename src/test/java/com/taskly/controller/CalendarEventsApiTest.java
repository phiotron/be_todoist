package com.taskly.controller;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.CalendarIntegrationTestBase;
import com.taskly.entity.CalendarProvider;
import com.taskly.integration.calendar.ExternalEvent;
import com.taskly.support.FakeCalendarClient;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** GET calendar/events (M13b): sự kiện chỉ đọc, đổi sang múi giờ của user, cửa sổ ≤ 31 ngày. */
class CalendarEventsApiTest extends CalendarIntegrationTestBase {

    private static final String EVENTS = "/api/v1/calendar/events";

    @Test
    void notConnectedOrHiddenReturnsAnEmptyList() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        authGet(user.accessToken(), EVENTS + "?from=2026-11-02&to=2026-11-02").andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.needsReauth").value(false));

        String email = uniqueCalendarEmail();
        connectCalendar(user.accessToken(), CalendarProvider.GOOGLE, email);
        fake(CalendarProvider.GOOGLE).account(email).primary.add(
                ExternalEvent.allDay("e1", "Họp", LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3)));
        patchCalendar(user.accessToken(), "{\"showEvents\": false}").andExpect(status().isOk());

        authGet(user.accessToken(), EVENTS + "?from=2026-11-02&to=2026-11-02")
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void allDayAndTimedEventsAreShownInTheUsersTimeZone() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        testData.setTimeZone(user.id(), "Asia/Ho_Chi_Minh");
        String email = uniqueCalendarEmail();
        connectCalendar(user.accessToken(), CalendarProvider.GOOGLE, email);
        FakeCalendarClient.Account account = fake(CalendarProvider.GOOGLE).account(email);
        account.primary.add(ExternalEvent.allDay("allday", "Nghỉ lễ", LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 4)));
        // 02:00Z = 09:00 giờ Việt Nam.
        account.primary.add(ExternalEvent.timed("timed", "Họp nhóm", Instant.parse("2026-11-02T02:00:00Z"),
                Instant.parse("2026-11-02T03:30:00Z")).withLocation("Phòng 3"));
        account.primary.add(ExternalEvent.timed("outside", "Ngày khác", Instant.parse("2026-11-20T02:00:00Z"),
                Instant.parse("2026-11-20T03:00:00Z")));

        authGet(user.accessToken(), EVENTS + "?from=2026-11-02&to=2026-11-03").andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value("allday"))
                .andExpect(jsonPath("$.items[0].allDay").value(true))
                .andExpect(jsonPath("$.items[0].startDate").value("2026-11-02"))
                .andExpect(jsonPath("$.items[0].endDate").value("2026-11-03"))
                .andExpect(jsonPath("$.items[0].startDatetime").doesNotExist())
                .andExpect(jsonPath("$.items[1].title").value("Họp nhóm"))
                .andExpect(jsonPath("$.items[1].startDatetime").value("2026-11-02T09:00:00"))
                .andExpect(jsonPath("$.items[1].endDatetime").value("2026-11-02T10:30:00"))
                .andExpect(jsonPath("$.items[1].location").value("Phòng 3"));
    }

    @Test
    void rangeIsValidated() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        authGet(user.accessToken(), EVENTS + "?from=2026-11-05&to=2026-11-02").andExpect(status().isBadRequest());
        authGet(user.accessToken(), EVENTS + "?from=2026-11-01&to=2026-12-15").andExpect(status().isBadRequest());
        authGet(user.accessToken(), EVENTS + "?from=2026-11-01&to=2026-12-01").andExpect(status().isOk());
        authGet(user.accessToken(), EVENTS + "?from=2026-11-01").andExpect(status().isBadRequest());
        authGet(user.accessToken(), EVENTS + "?from=bad&to=2026-11-02").andExpect(status().isBadRequest());
    }

    @Test
    void revokedAuthorizationFlagsReauthAndProviderOutageIs503() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String email = uniqueCalendarEmail();
        connectCalendar(user.accessToken(), CalendarProvider.OUTLOOK, email);
        FakeCalendarClient.Account account = fake(CalendarProvider.OUTLOOK).account(email);

        account.unavailable = true;
        authGet(user.accessToken(), EVENTS + "?from=2026-11-02&to=2026-11-02").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("calendar-unavailable"));
        account.unavailable = false;

        account.revoked = true;
        authGet(user.accessToken(), EVENTS + "?from=2026-11-02&to=2026-11-02").andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.needsReauth").value(true));
        authGet(user.accessToken(), "/api/v1/me/calendar").andExpect(jsonPath("$.needsReauth").value(true));
    }

    @Test
    void midnightEndBelongsToThePreviousDay() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        testData.setTimeZone(user.id(), "Asia/Ho_Chi_Minh");
        String email = uniqueCalendarEmail();
        connectCalendar(user.accessToken(), CalendarProvider.GOOGLE, email);
        // 15:00Z–17:00Z = 22:00–24:00 giờ Việt Nam.
        fake(CalendarProvider.GOOGLE).account(email).primary.add(ExternalEvent.timed("late", "Tối",
                Instant.parse("2026-11-02T15:00:00Z"), Instant.parse("2026-11-02T17:00:00Z")));

        authGet(user.accessToken(), EVENTS + "?from=2026-11-02&to=2026-11-03")
                .andExpect(jsonPath("$.items[0].startDate").value("2026-11-02"))
                .andExpect(jsonPath("$.items[0].endDate").value("2026-11-02"));
    }
}
