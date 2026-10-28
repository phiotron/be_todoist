package com.taskly.security;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * Token của địa chỉ nhận thư (M13a): 16 byte ngẫu nhiên (128 bit) mã base32 CHỮ THƯỜNG không padding → 26 ký tự
 * [a-z2-7]. Chữ thường vì nhiều hệ thống thư đổi hoa / thường phần trước @; base32 không có ký tự dễ nhầm như base64.
 * DB chỉ lưu SHA-256 (OpaqueTokens.sha256Hex) + bản mã.
 */
public final class EmailTokens {

    public static final int LENGTH = 26;
    private static final Pattern FORMAT = Pattern.compile("[a-z2-7]{" + LENGTH + "}");
    private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private EmailTokens() {
    }

    public static String generate() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder out = new StringBuilder(LENGTH);
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET[(buffer >> (bits - 5)) & 31]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(ALPHABET[(buffer << (5 - bits)) & 31]);
        }
        return out.toString();
    }

    /** Đúng định dạng token (đã đổi về chữ thường) — sai thì khỏi tra DB. */
    public static boolean isWellFormed(String token) {
        return token != null && FORMAT.matcher(token).matches();
    }

    public static String hash(String token) {
        return OpaqueTokens.sha256Hex(token);
    }
}
