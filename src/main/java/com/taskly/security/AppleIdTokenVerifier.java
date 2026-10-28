package com.taskly.security;

/**
 * Xác minh ID token "Sign in with Apple" (M13b). Bản chạy thật: {@link NimbusAppleIdTokenVerifier#withJwkSetUri}; test thay
 * bằng bản tin khoá RSA giả (không gọi Apple).
 */
public interface AppleIdTokenVerifier {

    /**
     * @throws InvalidExternalTokenException token sai chữ ký / hết hạn / sai iss / sai aud / thiếu sub hoặc email
     * @throws ExternalServiceUnavailableException không tải được JWKS của Apple
     */
    ExternalIdentity verify(String idToken);
}
