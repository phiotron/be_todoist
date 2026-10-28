package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.common.error.ProblemCodes;
import com.taskly.dto.calendar.CalendarEventResponse;
import com.taskly.dto.calendar.CalendarEventsResponse;
import com.taskly.entity.CalendarConnection;
import com.taskly.integration.calendar.CalendarAuthException;
import com.taskly.integration.calendar.ExternalEvent;
import com.taskly.security.ExternalServiceUnavailableException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * GET calendar/events (M13b): sự kiện CHỈ ĐỌC của lịch chính trong [from, to] (ngày theo múi giờ của user), đổi giờ sang
 * múi giờ của user (floating như task). Không lấy lịch "Taskly" (tránh trùng task). Không lưu sự kiện vào DB.
 */
@Service
public class CalendarEventService {

    private static final Logger log = LoggerFactory.getLogger(CalendarEventService.class);
    /** Như cửa sổ Upcoming (spec 12.3): tối đa 31 ngày một lần. */
    static final int MAX_RANGE_DAYS = 31;

    private final CalendarConnectionService connectionService;
    private final UserService userService;

    public CalendarEventService(CalendarConnectionService connectionService, UserService userService) {
        this.connectionService = connectionService;
        this.userService = userService;
    }

    public CalendarEventsResponse events(UUID userId, LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to) || ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw ApiException.badRequest(ErrorMessage.CALENDAR_RANGE_INVALID);
        }
        Optional<CalendarConnection> found = connectionService.find(userId);
        if (found.isEmpty() || !found.get().isShowEvents()) {
            return new CalendarEventsResponse(List.of(), false);
        }
        CalendarConnection connection = found.get();
        if (connection.isNeedsReauth()) {
            return new CalendarEventsResponse(List.of(), true);
        }
        ZoneId zone = userService.timeZoneOf(userId);
        try {
            CalendarConnectionService.Access access = connectionService.access(connection.getId());
            List<ExternalEvent> events = access.client().listEvents(access.accessToken(),
                    from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant());
            List<CalendarEventResponse> items = events.stream()
                    .filter(event -> !event.cancelled())
                    .map(event -> toResponse(event, zone))
                    .sorted(Comparator.comparing(CalendarEventResponse::startDate)
                            .thenComparing(event -> !event.allDay())
                            .thenComparing(event -> event.startDatetime() == null
                                    ? LocalDateTime.MIN : event.startDatetime()))
                    .toList();
            return new CalendarEventsResponse(items, false);
        } catch (CalendarAuthException ex) {
            connectionService.markNeedsReauth(connection.getId());
            return new CalendarEventsResponse(List.of(), true);
        } catch (ExternalServiceUnavailableException ex) {
            log.warn("Không đọc được sự kiện lịch: {}", CalendarConnectionService.causeName(ex));
            throw ApiException.serviceUnavailable(ErrorMessage.CALENDAR_UNAVAILABLE)
                    .withCode(ProblemCodes.CALENDAR_UNAVAILABLE);
        }
    }

    static CalendarEventResponse toResponse(ExternalEvent event, ZoneId zone) {
        String title = event.title() == null ? "" : event.title();
        if (event.allDay()) {
            LocalDate start = event.startDate();
            LocalDate endExclusive = event.endDateExclusive() == null ? start.plusDays(1) : event.endDateExclusive();
            LocalDate end = endExclusive.isAfter(start) ? endExclusive.minusDays(1) : start;
            return new CalendarEventResponse(event.id(), title, true, start, end, null, null, event.location());
        }
        LocalDateTime start = LocalDateTime.ofInstant(event.start(), zone).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime end = LocalDateTime.ofInstant(event.end(), zone).truncatedTo(ChronoUnit.SECONDS);
        // Kết thúc đúng 00:00 thuộc về ngày trước (sự kiện 22:00–00:00 chỉ nằm trong một ngày).
        LocalDate endDate = end.toLocalTime().equals(LocalTime.MIDNIGHT) && end.isAfter(start)
                ? end.toLocalDate().minusDays(1) : end.toLocalDate();
        if (endDate.isBefore(start.toLocalDate())) {
            endDate = start.toLocalDate();
        }
        return new CalendarEventResponse(event.id(), title, false, start.toLocalDate(), endDate, start, end,
                event.location());
    }
}
