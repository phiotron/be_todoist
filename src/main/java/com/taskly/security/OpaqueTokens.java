package com.taskly.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Token "mờ" (opaque) cho refresh token (và token lời mời email ở M4):
 * client giữ chuỗi thô, DB chỉ lưu SHA-256 của nó → lộ DB không dùng được token.
 */
public final class OpaqueTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private OpaqueTokens() {
    }

    /** 32 byte ngẫu nhiên, mã hoá base64url không padding → 43 ký tự [A-Za-z0-9_-]. */
    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 của chuỗi token (UTF-8), hex chữ thường 64 ký tự — giá trị được lưu ở cột token_hash. */
    public static String sha256Hex(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM không hỗ trợ SHA-256", e);
        }
    }
}
