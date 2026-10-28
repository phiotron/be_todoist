package com.taskly.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * taskly.calendar.* (M13b). Client id / secret của Google Calendar và Outlook (Microsoft Entra) đọc từ .env do người dùng
 * tự tạo; trống = nhà cung cấp đó trả 503 calendar-not-configured. redirect-uri = URL công khai của
 * GET /api/v1/calendar/oauth/callback (đăng ký ở cả hai nhà cung cấp). feed-base-url = tiền tố link iCal.
 */
@ConfigurationProperties(prefix = "taskly.calendar")
public record CalendarProperties(String redirectUri, String appRedirect, String feedBaseUrl, Provider google,
                                 Provider outlook, Integer windowPastDays, Integer windowFutureDays, Integer maxTasks,
                                 Integer defaultEventMinutes, Duration tokenRefreshMargin) {

    public static final String DEFAULT_APP_REDIRECT = "taskly://calendar/connected";
    public static final String DEFAULT_FEED_BASE_URL = "http://localhost:8080/api/v1/calendar/feed/";
    public static final String DEFAULT_REDIRECT_URI = "http://localhost:8080/api/v1/calendar/oauth/callback";

    public CalendarProperties {
        redirectUri = blank(redirectUri) ? DEFAULT_REDIRECT_URI : redirectUri.strip();
        appRedirect = blank(appRedirect) ? DEFAULT_APP_REDIRECT : appRedirect.strip();
        feedBaseUrl = blank(feedBaseUrl) ? DEFAULT_FEED_BASE_URL : feedBaseUrl.strip();
        if (!feedBaseUrl.endsWith("/")) {
            feedBaseUrl = feedBaseUrl + "/";
        }
        google = google == null ? new Provider(null, null, null) : google;
        outlook = outlook == null ? new Provider(null, null, null) : outlook;
        windowPastDays = windowPastDays == null ? 30 : windowPastDays;
        windowFutureDays = windowFutureDays == null ? 365 : windowFutureDays;
        maxTasks = maxTasks == null ? 500 : maxTasks;
        defaultEventMinutes = defaultEventMinutes == null ? 60 : defaultEventMinutes;
        tokenRefreshMargin = tokenRefreshMargin == null ? Duration.ofSeconds(60) : tokenRefreshMargin;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank() || value.startsWith("${");
    }

    /** clientSecret là bí mật: toString che. tenant chỉ dùng cho Outlook (mặc định "common"). */
    public record Provider(String clientId, String clientSecret, String tenant) {

        public Provider {
            clientId = blank(clientId) ? null : clientId.strip();
            clientSecret = blank(clientSecret) ? null : clientSecret.strip();
            tenant = blank(tenant) ? "common" : tenant.strip();
        }

        public boolean configured() {
            return clientId != null && clientSecret != null;
        }

        @Override
        public String toString() {
            return "Provider[clientId=" + clientId + ", clientSecret=***, tenant=" + tenant + "]";
        }
    }
}
