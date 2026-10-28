package com.taskly.integration.calendar;

import com.taskly.entity.CalendarProvider;
import java.time.Instant;
import java.util.List;

/**
 * Cổng tới API lịch của MỘT nhà cung cấp (M13b). Bản thật: {@link GoogleCalendarClient}, {@link OutlookCalendarClient};
 * test dùng bản giả. Mọi phương thức có thể ném:
 * - {@link CalendarAuthException}: token bị thu hồi / hết quyền → kết nối cần nối lại;
 * - {@link com.taskly.security.ExternalServiceUnavailableException}: mạng / 5xx / chưa cấu hình.
 * Không bao giờ log token.
 */
public interface CalendarClient {

    CalendarProvider provider();

    /** Đã có client id + secret chưa. */
    boolean configured();

    /** URL trang đồng ý OAuth (scope lịch + offline access để có refresh token). */
    String authorizationUrl(String state, String redirectUri);

    /** Đổi authorization code → token + email tài khoản lịch. */
    CalendarAccount exchangeCode(String code, String redirectUri);

    /** Làm mới access token; refreshToken của kết quả null = giữ cái cũ. */
    CalendarTokens refresh(String refreshToken);

    /** Sự kiện của lịch chính trong [from, to) (lặp đã bung từng lần; bỏ sự kiện đã huỷ). */
    List<ExternalEvent> listEvents(String accessToken, Instant from, Instant to);

    /** id lịch "Taskly": dùng knownCalendarId nếu còn, không thì tìm theo tên / tạo mới. */
    String ensureTasklyCalendar(String accessToken, String knownCalendarId);

    String createEvent(String accessToken, String calendarId, EventDraft draft);

    /** false = sự kiện không còn (người dùng đã xoá) → caller tạo lại. */
    boolean updateEvent(String accessToken, String calendarId, String eventId, EventDraft draft);

    /** Sự kiện đã mất cũng coi là xong. */
    void deleteEvent(String accessToken, String calendarId, String eventId);

    /** Sự kiện của lịch "Taskly" đổi từ since (gồm cả sự kiện đã huỷ / xoá nếu nhà cung cấp trả). */
    List<ExternalEvent> changedEvents(String accessToken, String calendarId, Instant since);
}
