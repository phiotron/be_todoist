package com.taskly.service;

import com.taskly.dto.notification.NotificationPrefsResponse;
import com.taskly.entity.User;
import com.taskly.repository.DigestQuery;
import com.taskly.repository.DigestQuery.DigestTask;
import com.taskly.repository.NotificationPrefsRepository;
import com.taskly.repository.NotificationPrefsRepository.DigestUser;
import com.taskly.repository.ReminderOccurrenceQuery;
import com.taskly.repository.ReminderOccurrenceRow;
import com.taskly.security.AccessPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

/**
 * Quyết định "ai cần được thông báo gì lúc này" rồi xếp vào hàng đợi (plan M13c, F6-F7). Chạy định kỳ; chống trùng nhờ
 * khoá (user, kênh, dedupe_key). Giờ theo múi giờ tài khoản: Morning overview gửi trong [07:00, 11:00) mỗi ngày,
 * Evening review trong [20:00, 24:00); nhắc việc: lần nhắc rơi vào 5 phút vừa qua (cùng quy tắc nhắc của M4).
 */
@Service
public class NotificationPlanner {

    static final int MORNING_FROM = 7;
    static final int MORNING_UNTIL = 11;
    static final int EVENING_FROM = 20;
    static final int REMINDER_LOOKBACK_MINUTES = 5;
    static final int DIGEST_LINES = 20;

    private static final Logger log = LoggerFactory.getLogger(NotificationPlanner.class);

    private final NotificationPrefsRepository prefsRepository;
    private final NotificationPrefsService prefsService;
    private final NotificationDeliveryService delivery;
    private final ReminderOccurrenceQuery occurrences;
    private final DigestQuery digestQuery;
    private final AccessPolicy accessPolicy;
    private final UserService userService;
    private final MessageSource messages;

    public NotificationPlanner(NotificationPrefsRepository prefsRepository, NotificationPrefsService prefsService,
                               NotificationDeliveryService delivery, ReminderOccurrenceQuery occurrences,
                               DigestQuery digestQuery, AccessPolicy accessPolicy, UserService userService,
                               MessageSource messages) {
        this.prefsRepository = prefsRepository;
        this.prefsService = prefsService;
        this.delivery = delivery;
        this.occurrences = occurrences;
        this.digestQuery = digestQuery;
        this.accessPolicy = accessPolicy;
        this.userService = userService;
        this.messages = messages;
    }

    /** Xếp nhắc việc (push / email) cho lần nhắc vừa tới. Trả số thông báo mới xếp hàng. */
    public int planReminders(Instant now) {
        int queued = 0;
        for (UUID userId : prefsRepository.usersWithReminders()) {
            try {
                queued += planRemindersFor(userId, now);
            } catch (RuntimeException e) {
                log.warn("Không lập được nhắc cho một người dùng ({})", e.getClass().getSimpleName());
            }
        }
        return queued;
    }

    private int planRemindersFor(UUID userId, Instant now) {
        User user = userService.requireUser(userId);
        NotificationPrefsResponse prefs = prefsService.get(userId);
        LocalDateTime nowLocal = LocalDateTime.ofInstant(now, ZoneId.of(user.getTimeZone())).truncatedTo(ChronoUnit.MINUTES);
        Locale locale = Locale.forLanguageTag(user.getLocale());
        List<ReminderOccurrenceRow> due = occurrences
                .find(userId, accessPolicy.readableWorkspaceIds(userId),
                        nowLocal.minusMinutes(REMINDER_LOOKBACK_MINUTES), ReminderWindow.MAX_LIMIT)
                .stream().filter(row -> !row.fireAt().isAfter(nowLocal)).toList();
        int queued = 0;
        for (ReminderOccurrenceRow row : due) {
            String key = "reminder:" + row.taskId() + ":" + (row.reminderId() == null ? "auto" : row.reminderId())
                    + ":" + row.fireAt();
            if (prefs.reminderPush()) {
                Map<String, String> data = new LinkedHashMap<>();
                data.put("kind", "reminder");
                data.put("taskId", row.taskId().toString());
                queued += delivery.enqueue(userId, NotificationDeliveryService.PUSH, "REMINDER", key, row.content(),
                        messages.getMessage("push.reminder.body", null, locale), data) ? 1 : 0;
            }
            if (prefs.reminderEmail()) {
                queued += delivery.enqueue(userId, NotificationDeliveryService.EMAIL, "REMINDER", key,
                        messages.getMessage("mail.reminder.subject", new Object[] {row.content()}, locale),
                        messages.getMessage("mail.reminder.body", new Object[] {user.getDisplayName(), row.content()},
                                locale), null) ? 1 : 0;
            }
        }
        return queued;
    }

    /** Xếp email Morning overview / Evening review nếu đang trong khung giờ và hôm nay chưa gửi. */
    public int planDigests(Instant now) {
        int queued = 0;
        for (DigestUser user : prefsRepository.usersWithDigests()) {
            try {
                queued += planDigestsFor(user, now);
            } catch (RuntimeException e) {
                log.warn("Không lập được email tóm tắt cho một người dùng ({})", e.getClass().getSimpleName());
            }
        }
        return queued;
    }

    private int planDigestsFor(DigestUser user, Instant now) {
        ZoneId zone = ZoneId.of(user.timeZone());
        ZonedDateTime local = now.atZone(zone);
        LocalDate today = local.toLocalDate();
        int hour = local.getHour();
        List<UUID> workspaces = accessPolicy.readableWorkspaceIds(user.id());
        Locale locale = Locale.forLanguageTag(user.locale());
        int queued = 0;
        if (user.morning() && hour >= MORNING_FROM && hour < MORNING_UNTIL) {
            int total = digestQuery.countOpenDueUpTo(workspaces, today);
            String list = total == 0 ? messages.getMessage("mail.morning.empty", null, locale)
                    : lines(digestQuery.openDueUpTo(workspaces, today, DIGEST_LINES), total, today, locale);
            queued += delivery.enqueue(user.id(), NotificationDeliveryService.EMAIL, "MORNING", "morning:" + today,
                    messages.getMessage("mail.morning.subject", new Object[] {total}, locale),
                    messages.getMessage("mail.morning.body", new Object[] {user.displayName(), list,
                            messages.getMessage("mail.digest.footer", null, locale)}, locale), null) ? 1 : 0;
        }
        if (user.evening() && hour >= EVENING_FROM) {
            Instant dayStart = today.atStartOfDay(zone).toInstant();
            Instant dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant();
            int completed = digestQuery.countCompletedBetween(workspaces, dayStart, dayEnd);
            int remaining = digestQuery.countOpenDueUpTo(workspaces, today);
            String list = remaining == 0 ? messages.getMessage("mail.evening.empty", null, locale)
                    : lines(digestQuery.openDueUpTo(workspaces, today, DIGEST_LINES), remaining, today, locale);
            int tomorrow = digestQuery.countOpenDueOn(workspaces, today.plusDays(1));
            queued += delivery.enqueue(user.id(), NotificationDeliveryService.EMAIL, "EVENING", "evening:" + today,
                    messages.getMessage("mail.evening.subject", new Object[] {completed}, locale),
                    messages.getMessage("mail.evening.body", new Object[] {user.displayName(), completed, remaining,
                            list, tomorrow, messages.getMessage("mail.digest.footer", null, locale)}, locale),
                    null) ? 1 : 0;
        }
        return queued;
    }

    private String lines(List<DigestTask> tasks, int total, LocalDate today, Locale locale) {
        String overdue = messages.getMessage("mail.digest.overdue", null, locale);
        String text = tasks.stream()
                .map(task -> "- " + task.content() + (task.dueDay().isBefore(today) ? " (" + overdue + ")" : ""))
                .collect(Collectors.joining("\n"));
        if (total > tasks.size()) {
            text += "\n" + messages.getMessage("mail.digest.more", new Object[] {total - tasks.size()}, locale);
        }
        return text;
    }
}
