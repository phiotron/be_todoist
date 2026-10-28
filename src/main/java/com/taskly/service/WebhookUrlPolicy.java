package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.config.WebhookProperties;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** URL webhook hợp lệ + chặn gửi tới địa chỉ nội bộ (SSRF) trừ khi taskly.webhooks.allow-insecure. */
@Component
public class WebhookUrlPolicy {

    static final int MAX_LENGTH = 2048;

    private final boolean allowInsecure;

    public WebhookUrlPolicy(WebhookProperties properties) {
        this.allowInsecure = properties.allowInsecure();
    }

    /** Lúc cấu hình webhook: https (http chỉ khi allowInsecure), có host, không user-info, ≤ 2048. Sai → 400. */
    public String validate(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty() || value.length() > MAX_LENGTH) {
            throw invalid();
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw invalid();
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean schemeOk = "https".equals(scheme) || (allowInsecure && "http".equals(scheme));
        if (!schemeOk || uri.getHost() == null || uri.getHost().isBlank() || uri.getRawUserInfo() != null
                || uri.getFragment() != null) {
            throw invalid();
        }
        return value;
    }

    /** Lúc gửi: false nếu host phân giải ra địa chỉ nội bộ (và không allowInsecure) hoặc không phân giải được. */
    public boolean isSafeToCall(String url) {
        if (allowInsecure) {
            return true;
        }
        try {
            String host = URI.create(url).getHost();
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (isInternal(address)) {
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException | RuntimeException e) {
            return false;
        }
    }

    static boolean isInternal(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            return (bytes[0] & 0xFE) == 0xFC; // fc00::/7 (unique local)
        }
        return (bytes[0] & 0xFF) == 100 && (bytes[1] & 0xC0) == 64; // 100.64.0.0/10 (CGNAT)
    }

    private static ApiException invalid() {
        return ApiException.badRequest(ErrorMessage.WEBHOOK_URL_INVALID);
    }
}
