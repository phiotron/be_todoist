package com.taskly.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * taskly.facebook.* (M13b) — FACEBOOK_APP_ID, FACEBOOK_APP_SECRET (bí mật, chỉ ở .env), FACEBOOK_REDIRECT_URI (URL công khai
 * của GET /api/v1/auth/facebook/callback, đăng ký trong Meta for Developers). app-id trống = từ chối mọi token Facebook.
 */
@ConfigurationProperties(prefix = "taskly.facebook")
public record FacebookProperties(String appId, String appSecret, String redirectUri, String graphBaseUrl,
                                 String appRedirect) {

    public static final String DEFAULT_GRAPH_BASE_URL = "https://graph.facebook.com/v19.0";
    public static final String DEFAULT_APP_REDIRECT = "taskly://oauth/facebook";

    public FacebookProperties {
        appId = blankToNull(appId);
        appSecret = blankToNull(appSecret);
        redirectUri = blankToNull(redirectUri);
        graphBaseUrl = graphBaseUrl == null || graphBaseUrl.isBlank() ? DEFAULT_GRAPH_BASE_URL : graphBaseUrl.strip();
        appRedirect = appRedirect == null || appRedirect.isBlank() ? DEFAULT_APP_REDIRECT : appRedirect.strip();
    }

    public boolean configured() {
        return appId != null && appSecret != null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() || value.startsWith("${") ? null : value.strip();
    }

    /** Không in app secret (record mặc định in mọi field). */
    @Override
    public String toString() {
        return "FacebookProperties[appId=" + appId + ", appSecret=***, redirectUri=" + redirectUri + "]";
    }
}
