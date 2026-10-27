package com.taskly.service;

import com.taskly.dto.reminder.ReminderOccurrence;
import com.taskly.mapper.ReminderOccurrenceMapper;
import com.taskly.repository.ReminderOccurrenceQuery;
import com.taskly.security.AccessPolicy;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lịch nhắc cho app đặt trên máy (spec 7: BE tính thời điểm, FE đặt lịch). Không lọc theo remindersEnabled — app
 * áp dụng cờ đó (spec 12.8). Tách khỏi ReminderService (CRUD) để hai phần làm song song.
 */
@Service
@Transactional(readOnly = true)
public class ReminderOccurrenceService {

    private final ReminderOccurrenceQuery reminderOccurrenceQuery;
    private final AccessPolicy accessPolicy;
    private final UserService userService;
    private final Clock clock;

    public ReminderOccurrenceService(ReminderOccurrenceQuery reminderOccurrenceQuery, AccessPolicy accessPolicy,
                                     UserService userService, Clock clock) {
        this.reminderOccurrenceQuery = reminderOccurrenceQuery;
        this.accessPolicy = accessPolicy;
        this.userService = userService;
        this.clock = clock;
    }

    /** requestedLimit null → 50; ngoài 1..50 → 400. User không còn → 401 (UserService.timeZoneOf). */
    public List<ReminderOccurrence> upcoming(UUID userId, Integer requestedLimit) {
        int limit = ReminderWindow.limit(requestedLimit);
        ReminderWindow window = ReminderWindow.at(clock.instant(), userService.timeZoneOf(userId));
        return reminderOccurrenceQuery.find(userId, accessPolicy.readableWorkspaceIds(userId), window.now(), limit)
                .stream()
                .map(ReminderOccurrenceMapper::toResponse)
                .toList();
    }
}
