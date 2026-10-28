package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Luật Redirect URI của app (plan M13c, F1): URI tuyệt đối, không fragment, ≤ 2048 ký tự; https, hoặc http chỉ với
 * localhost / 127.0.0.1 / [::1], hoặc scheme riêng của app (cấm javascript, data, file, taskly...). Thuần, test bảng.
 */
public final class RedirectUris {

    public static final int MAX_LENGTH = 2048;

    private static final Pattern SCHEME = Pattern.compile("[a-z][a-z0-9+.-]*");
    private static final Set<String> FORBIDDEN_SCHEMES = Set.of(
            "javascript", "data", "file", "taskly", "vbscript", "about", "blob", "content", "intent", "ftp");
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private RedirectUris() {
    }

    /** Trả URI đã strip; sai luật → 400 error.redirect-uri-invalid. */
    public static String validate(String raw) {
        if (raw == null) {
            throw invalid();
        }
        String value = raw.strip();
        if (value.isEmpty() || value.length() > MAX_LENGTH) {
            throw invalid();
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw invalid();
        }
        if (!uri.isAbsolute() || uri.getFragment() != null || uri.getRawUserInfo() != null) {
            throw invalid();
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!SCHEME.matcher(scheme).matches() || FORBIDDEN_SCHEMES.contains(scheme)) {
            throw invalid();
        }
        if ("https".equals(scheme) && (uri.getHost() == null || uri.getHost().isBlank())) {
            throw invalid();
        }
        if ("http".equals(scheme)
                && (uri.getHost() == null || !LOOPBACK_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT)))) {
            throw invalid();
        }
        return value;
    }

    private static ApiException invalid() {
        return ApiException.badRequest(ErrorMessage.REDIRECT_URI_INVALID);
    }
}
