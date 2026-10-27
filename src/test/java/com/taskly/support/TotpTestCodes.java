package com.taskly.support;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Sinh mã TOTP RFC 6238 (HMAC-SHA1, 6 số, bước 30 giây — spec 5.9) từ secret base32 mà POST me/2fa/setup trả về.
 * CHỈ dùng trong test; không in secret / mã ra log.
 */
public final class TotpTestCodes {

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final long STEP_SECONDS = 30;

    private TotpTestCodes() {
    }

    public static String code(String base32Secret, Instant at) {
        byte[] message = ByteBuffer.allocate(Long.BYTES).putLong(at.getEpochSecond() / STEP_SECONDS).array();
        byte[] hash;
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(base32Decode(base32Secret), "HmacSHA1"));
            hash = mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 unavailable", e);
        }
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
        return String.format(Locale.ROOT, "%06d", binary % 1_000_000);
    }

    static byte[] base32Decode(String value) {
        String clean = value.replace("=", "").replace(" ", "").toUpperCase(Locale.ROOT);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : clean.toCharArray()) {
            int index = BASE32_ALPHABET.indexOf(c);
            if (index < 0) {
                throw new IllegalArgumentException("Not a base32 secret");
            }
            buffer = ((buffer << 5) | index) & 0xffff;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
