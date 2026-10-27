package com.taskly.security;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TOTP RFC 6238 (spec mục 5.9): HMAC-SHA1, 6 số, bước 30 giây, chấp nhận lệch ±1 bước, không nhận lại bước đã dùng.
 * Hàm thuần (không Spring, không log): secret thô KHÔNG bao giờ được log; DB chỉ giữ bản mã hoá (SecretCipher).
 */
public final class TotpCodec {

    public static final int DIGITS = 6;
    public static final long PERIOD_SECONDS = 30;
    public static final int ALLOWED_DRIFT_STEPS = 1;
    public static final int SECRET_BYTES = 20;

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final Pattern WELL_FORMED_CODE = Pattern.compile("[0-9]{6}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private TotpCodec() {
    }

    /** Secret mới: 20 byte ngẫu nhiên (160 bit, đúng khuyến nghị RFC 4226). */
    public static byte[] newSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return secret;
    }

    /** Số thứ tự bước 30 giây tính từ epoch. */
    public static long step(Instant instant) {
        return Math.floorDiv(instant.getEpochSecond(), PERIOD_SECONDS);
    }

    /** Mã 6 số của một bước. */
    public static String code(byte[] secret, long step) {
        return code(secret, step, DIGITS);
    }

    /** HOTP(secret, step) theo RFC 4226 với số chữ số tuỳ chọn (8 số chỉ để đối chiếu vector RFC trong test). */
    static String code(byte[] secret, long step, int digits) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            int modulus = (int) Math.pow(10, digits);
            return String.format(Locale.ROOT, "%0" + digits + "d", binary % modulus);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JVM không hỗ trợ HmacSHA1", e);
        }
    }

    /** Đúng 6 chữ số ASCII. */
    public static boolean isWellFormed(String code) {
        return code != null && WELL_FORMED_CODE.matcher(code).matches();
    }

    /**
     * Bước khớp với mã trong cửa sổ [bước hiện tại − 1, bước hiện tại + 1], bỏ qua mọi bước ≤ lastUsedStep (chống dùng
     * lại). Không khớp / mã sai dạng → rỗng.
     */
    public static OptionalLong matchingStep(byte[] secret, String code, Instant now, Long lastUsedStep) {
        if (!isWellFormed(code)) {
            return OptionalLong.empty();
        }
        byte[] given = code.getBytes(StandardCharsets.US_ASCII);
        long current = step(now);
        for (long candidate = current - ALLOWED_DRIFT_STEPS; candidate <= current + ALLOWED_DRIFT_STEPS; candidate++) {
            if (lastUsedStep != null && candidate <= lastUsedStep) {
                continue;
            }
            byte[] expected = code(secret, candidate).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(given, expected)) {
                return OptionalLong.of(candidate);
            }
        }
        return OptionalLong.empty();
    }

    /** Base32 RFC 4648, chữ hoa, không padding (dạng app authenticator nhận). */
    public static String base32(byte[] bytes) {
        StringBuilder out = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32_ALPHABET.charAt((buffer >>> (bits - 5)) & 31));
                bits -= 5;
            }
            buffer &= (1 << bits) - 1;
        }
        if (bits > 0) {
            out.append(BASE32_ALPHABET.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    /** Ngược của base32(): chỉ nhận A–Z, 2–7 (chữ hoa, không padding). */
    public static byte[] fromBase32(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Chuỗi base32 rỗng");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(text.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < text.length(); i++) {
            int value = BASE32_ALPHABET.indexOf(text.charAt(i));
            if (value < 0) {
                throw new IllegalArgumentException("Ký tự base32 không hợp lệ ở vị trí " + i);
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >>> (bits - 8)) & 0xff);
                bits -= 8;
                buffer &= (1 << bits) - 1;
            }
        }
        return out.toByteArray();
    }

    /** URI cho QR / nhập tay vào app authenticator (Google Authenticator, Aegis, 1Password…). */
    public static String otpauthUri(String issuer, String accountName, String base32Secret) {
        return "otpauth://totp/" + encode(issuer) + ":" + encode(accountName)
                + "?secret=" + base32Secret
                + "&issuer=" + encode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD_SECONDS;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
