package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.common.error.ProblemCodes;
import com.taskly.config.CalendarProperties;
import com.taskly.dto.calendar.CalendarSyncResponse;
import com.taskly.dto.task.UpdateTaskRequest;
import com.taskly.entity.CalendarConnection;
import com.taskly.entity.CalendarTaskLink;
import com.taskly.entity.Task;
import com.taskly.integration.calendar.CalendarAuthException;
import com.taskly.integration.calendar.CalendarClient;
import com.taskly.integration.calendar.EventDraft;
import com.taskly.integration.calendar.ExternalEvent;
import com.taskly.repository.CalendarConnectionRepository;
import com.taskly.repository.CalendarTaskLinkRepository;
import com.taskly.repository.CalendarTaskRepository;
import com.taskly.repository.TaskRepository;
import com.taskly.security.AccessPolicy;
import com.taskly.security.ExternalServiceUnavailableException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * "Sync tasks to calendar" (M13b, như Todoist — KHÔNG hai chiều đầy đủ):
 * 1) kéo ngược có giới hạn: sự kiện trong lịch "Taskly" đổi ngày / giờ / độ dài → cập nhật task KHÔNG lặp, chỉ khi task
 *    chưa bị sửa ở Taskly từ lần đẩy trước (Taskly thắng khi cả hai cùng sửa). Task lặp: bỏ qua (đẩy đè lại);
 * 2) đẩy: task đủ điều kiện (chưa xong, có ngày trong cửa sổ, dự án / section không lưu trữ, task của mình) → tạo / sửa
 *    sự kiện; task không còn đủ điều kiện → xoá sự kiện. Tắt syncTasks → xoá mọi sự kiện đã đẩy.
 * Không giữ transaction trong lúc gọi mạng: mỗi lần ghi DB là một transaction ngắn (repository / TaskService).
 * Một kết nối chỉ sync một luồng tại một thời điểm (khoá trong bộ nhớ — triển khai một instance).
 */
@Service
public class CalendarSyncService {

    private static final Logger log = LoggerFactory.getLogger(CalendarSyncService.class);

    private final CalendarConnectionService connectionService;
    private final CalendarConnectionRepository connections;
    private final CalendarTaskLinkRepository links;
    private final CalendarTaskRepository calendarTasks;
    private final TaskRepository taskRepository;
    private final TaskService taskService;
    private final AccessPolicy accessPolicy;
    private final UserService userService;
    private final CalendarProperties properties;
    private final Clock clock;
    private final Map<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    public CalendarSyncService(CalendarConnectionService connectionService, CalendarConnectionRepository connections,
                               CalendarTaskLinkRepository links, CalendarTaskRepository calendarTasks,
                               TaskRepository taskRepository, TaskService taskService, AccessPolicy accessPolicy,
                               UserService userService, CalendarProperties properties, Clock clock) {
        this.connectionService = connectionService;
        this.connections = connections;
        this.links = links;
        this.calendarTasks = calendarTasks;
        this.taskRepository = taskRepository;
        this.taskService = taskService;
        this.accessPolicy = accessPolicy;
        this.userService = userService;
        this.properties = properties;
        this.clock = clock;
    }

    /** POST me/calendar/sync. Chưa nối → 404; cần nối lại → 409 kèm needsReauth trong trạng thái (GET me/calendar). */
    public CalendarSyncResponse syncNow(UUID userId) {
        CalendarConnection connection = connectionService.find(userId)
                .orElseThrow(() -> ApiException.notFound(ErrorMessage.CALENDAR_NOT_CONNECTED));
        try {
            return sync(connection.getId());
        } catch (CalendarAuthException ex) {
            return CalendarSyncResponse.none();
        } catch (ExternalServiceUnavailableException ex) {
            log.warn("Đồng bộ lịch thất bại: {}", CalendarConnectionService.causeName(ex));
            throw ApiException.serviceUnavailable(ErrorMessage.CALENDAR_UNAVAILABLE)
                    .withCode(ProblemCodes.CALENDAR_UNAVAILABLE);
        }
    }

    /** Job nền: mọi kết nối đang bật sync (hoặc còn sự kiện cần dọn). Lỗi một user không chặn user khác. */
    public void syncAll() {
        for (UUID userId : connections.findUserIdsToSync()) {
            try {
                connectionService.find(userId).ifPresent(connection -> sync(connection.getId()));
            } catch (RuntimeException ex) {
                log.warn("Đồng bộ lịch của một user thất bại: {}", ex.getClass().getSimpleName());
            }
        }
    }

    CalendarSyncResponse sync(UUID connectionId) {
        ReentrantLock lock = locks.computeIfAbsent(connectionId, id -> new ReentrantLock());
        if (!lock.tryLock()) {
            return CalendarSyncResponse.none(); // đang sync ở luồng khác
        }
        try {
            CalendarConnectionService.Access access = connectionService.access(connectionId);
            try {
                return syncLocked(access);
            } catch (CalendarAuthException ex) {
                connectionService.markNeedsReauth(connectionId);
                throw ex;
            }
        } finally {
            lock.unlock();
        }
    }

    private CalendarSyncResponse syncLocked(CalendarConnectionService.Access access) {
        CalendarConnection connection = access.connection();
        CalendarClient client = access.client();
        String token = access.accessToken();
        UUID userId = connection.getUserId();
        Map<UUID, CalendarTaskLink> linked = links.findByConnectionId(connection.getId()).stream()
                .collect(Collectors.toMap(CalendarTaskLink::getTaskId, Function.identity()));
        if (!connection.isSyncTasks()) {
            int deleted = 0;
            for (CalendarTaskLink link : linked.values()) {
                if (connection.getTasklyCalendarId() != null) {
                    client.deleteEvent(token, connection.getTasklyCalendarId(), link.getEventId());
                }
                links.delete(link);
                deleted++;
            }
            return new CalendarSyncResponse(0, 0, deleted, 0);
        }
        String calendarId = client.ensureTasklyCalendar(token, connection.getTasklyCalendarId());
        if (!calendarId.equals(connection.getTasklyCalendarId())) {
            saveCalendarId(connection.getId(), calendarId);
            linked.values().forEach(links::delete); // lịch cũ đã mất → mọi sự kiện cũ cũng mất
            linked.clear();
        }
        ZoneId zone = userService.timeZoneOf(userId);
        int minutes = properties.defaultEventMinutes();
        Instant startedAt = Instant.now(clock);

        int pulled = connection.getLastSyncedAt() == null || linked.isEmpty() ? 0
                : pull(client, token, calendarId, connection.getLastSyncedAt(), linked, userId, zone, minutes);

        LocalDate today = LocalDate.now(clock.withZone(zone));
        List<Task> eligible = calendarTasks.findScheduled(accessPolicy.listScope(userId),
                today.minusDays(properties.windowPastDays()), today.plusDays(properties.windowFutureDays()),
                properties.maxTasks());
        int created = 0;
        int updated = 0;
        for (Task task : eligible) {
            EventDraft draft = CalendarTaskEvents.draftOf(task, zone, minutes);
            String fingerprint = CalendarTaskEvents.fingerprint(draft);
            CalendarTaskLink link = linked.remove(task.getId());
            if (link == null) {
                String eventId = client.createEvent(token, calendarId, draft);
                links.save(new CalendarTaskLink(connection.getId(), task.getId(), eventId, fingerprint,
                        task.getVersion()));
                created++;
            } else if (!fingerprint.equals(link.getFingerprint())) {
                String eventId = link.getEventId();
                if (!client.updateEvent(token, calendarId, eventId, draft)) {
                    eventId = client.createEvent(token, calendarId, draft); // người dùng đã xoá sự kiện → tạo lại
                }
                link.pushed(eventId, fingerprint, task.getVersion());
                links.save(link);
                updated++;
            } else if (link.getTaskVersion() != task.getVersion()) {
                link.pushed(link.getEventId(), fingerprint, task.getVersion());
                links.save(link);
            }
        }
        int deleted = 0;
        for (CalendarTaskLink stale : linked.values()) {
            client.deleteEvent(token, calendarId, stale.getEventId());
            links.delete(stale);
            deleted++;
        }
        markSynced(connection.getId(), startedAt);
        return new CalendarSyncResponse(created, updated, deleted, pulled);
    }

    private int pull(CalendarClient client, String token, String calendarId, Instant since,
                     Map<UUID, CalendarTaskLink> linked, UUID userId, ZoneId zone, int minutes) {
        Map<String, CalendarTaskLink> byEvent = new HashMap<>();
        linked.values().forEach(link -> byEvent.put(link.getEventId(), link));
        int pulled = 0;
        for (ExternalEvent event : client.changedEvents(token, calendarId, since)) {
            CalendarTaskLink link = byEvent.get(event.id());
            if (link == null) {
                continue;
            }
            Optional<Task> found = taskRepository.findById(link.getTaskId());
            if (found.isEmpty()) {
                continue;
            }
            Task task = found.get();
            boolean recurring = task.getRecurrenceRule() != null && !task.getRecurrenceRule().isBlank();
            if (recurring || task.getCompletedAt() != null || task.getVersion() != link.getTaskVersion()) {
                continue; // task lặp: thay đổi ngược bị bỏ qua; Taskly đã sửa: Taskly thắng
            }
            CalendarTaskEvents.PulledDue due = CalendarTaskEvents.pulledDue(event, task, zone, minutes);
            if (due == null) {
                continue;
            }
            try {
                taskService.update(userId, task.getId(), new UpdateTaskRequest(task.getVersion(), null, null, null,
                        Optional.ofNullable(due.dueDate()), Optional.ofNullable(due.dueDatetime()),
                        due.durationMinutes() == null ? null : Optional.of(due.durationMinutes()), null, null,
                        null, null, null, null));
                pulled++;
            } catch (ApiException ex) {
                // Task vừa bị sửa / lưu trữ / mất quyền: bỏ qua, lần đẩy sau ghi đè sự kiện theo Taskly.
                log.debug("Bỏ qua thay đổi ngược của một task: {}", ex.getStatusCode());
            }
        }
        return pulled;
    }

    private void saveCalendarId(UUID connectionId, String calendarId) {
        connections.updateTasklyCalendarId(connectionId, calendarId);
    }

    private void markSynced(UUID connectionId, Instant at) {
        connections.markSynced(connectionId, at);
    }
}
