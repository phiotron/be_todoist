package com.taskly.security;

import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * taskly.apple.* (M13b). client-ids ← APPLE_CLIENT_IDS (Services ID dùng cho luồng web trên Android, có thể thêm bundle ID
 * iOS sau này), cách nhau bởi dấu phẩy; để trống = từ chối mọi token. app-redirect = deep link app nhận kết quả.
 */
@ConfigurationProperties(prefix = "taskly.apple")
public record AppleProperties(List<String> clientIds, String jwkSetUri, String appRedirect) {

    public static final String APPLE_ISSUER = "https://appleid.apple.com";
    public static final String APPLE_JWK_SET_URI = "https://appleid.apple.com/auth/keys";
    public static final String DEFAULT_APP_REDIRECT = "taskly://oauth/apple";

    public AppleProperties {
        clientIds = clientIds == null ? List.of() : clientIds.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(id -> !id.isEmpty())
                .toList();
        jwkSetUri = jwkSetUri == null || jwkSetUri.isBlank() ? APPLE_JWK_SET_URI : jwkSetUri.strip();
        appRedirect = appRedirect == null || appRedirect.isBlank() ? DEFAULT_APP_REDIRECT : appRedirect.strip();
    }
}
