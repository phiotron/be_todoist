package com.taskly.service;

import com.taskly.dto.notification.NotificationPrefsResponse;
import com.taskly.dto.notification.UpdateNotificationPrefsRequest;
import com.taskly.repository.NotificationPrefsRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cài đặt thông báo từ server (plan M13c, F7). Chưa có hàng = mặc định: Subscribed emails bật, các mục khác tắt (nhắc
 * qua push từ server tắt mặc định vì M4 đã nhắc cục bộ trên máy — bật cả hai thì máy đang dùng có thể nhận hai lần).
 */
@Service
@Transactional
public class NotificationPrefsService {

    static final NotificationPrefsResponse DEFAULTS = new NotificationPrefsResponse(false, false, false, false, true);

    private final NotificationPrefsRepository prefs;
    private final Clock clock;

    public NotificationPrefsService(NotificationPrefsRepository prefs, Clock clock) {
        this.prefs = prefs;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public NotificationPrefsResponse get(UUID userId) {
        return prefs.find(userId).orElse(DEFAULTS);
    }

    /** Trường vắng hoặc null giữ nguyên; luôn ghi hàng (lần đầu tạo từ mặc định). */
    public NotificationPrefsResponse update(UUID userId, UpdateNotificationPrefsRequest request) {
        NotificationPrefsResponse current = get(userId);
        if (request == null) {
            return current;
        }
        NotificationPrefsResponse updated = new NotificationPrefsResponse(
                pick(request.reminderPush(), current.reminderPush()),
                pick(request.reminderEmail(), current.reminderEmail()),
                pick(request.morningOverview(), current.morningOverview()),
                pick(request.eveningReview(), current.eveningReview()),
                pick(request.subscribedEmails(), current.subscribedEmails()));
        prefs.upsert(userId, updated, clock.instant());
        return updated;
    }

    private static boolean pick(Boolean requested, boolean current) {
        return requested == null ? current : requested;
    }
}
