package com.taskly.security;

import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * taskly.google.* — client-ids lấy từ biến GOOGLE_CLIENT_IDS trong .env (người dùng tự điền, cách nhau bởi dấu phẩy).
 * aud hợp lệ của ID token = Web client ID (app Android truyền nó làm serverClientId của Credential Manager).
 * Client ID không phải bí mật (nằm trong app), nhưng mỗi người tự tạo ở Google Cloud Console.
 */
@ConfigurationProperties(prefix = "taskly.google")
public record GoogleProperties(List<String> clientIds, String jwkSetUri) {

    /** jwks_uri trong https://accounts.google.com/.well-known/openid-configuration */
    public static final String GOOGLE_JWK_SET_URI = "https://www.googleapis.com/oauth2/v3/certs";

    public GoogleProperties {
        clientIds = clientIds == null ? List.of() : clientIds.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(id -> !id.isEmpty())
                .toList();
        jwkSetUri = jwkSetUri == null || jwkSetUri.isBlank() ? GOOGLE_JWK_SET_URI : jwkSetUri.strip();
    }
}
